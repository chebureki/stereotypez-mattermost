package de.stereotypez.mattermost.ws.bot

import java.util.logging.Level

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.{Flow, Sink}
import Bot.Hook
import de.stereotypez.mattermost.ws.Protocol.{EventMessage, MattermostMessage, StatusMessage}
import de.stereotypez.mattermost.ws.ServiceActorProtocol.{Connect, Disconnect, ServiceActorCommand}
import de.stereotypez.mattermost.ws.{MatterMostWsProtocolException, ServiceActor}
import net.bis5.mattermost.client4.MattermostClient
import spray.json._

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import akka.NotUsed

object Bot {
  case class HookResult(handled: Boolean, consumed: Boolean)
  type Hook = MattermostMessage => HookResult

  @deprecated("Use BotActor", "2021-08-09")
  def apply(mmUrl: String, wsUrl: String, botToken: String, system: akka.actor.ActorSystem) = {
    new Bot(mmUrl, wsUrl, botToken, system)
  }
}

@deprecated("Use BotActor", "2021-08-09")
class Bot(mmUrl: String, wsUrl: String, botToken: String, system: akka.actor.ActorSystem) {

  private implicit val ec = system.getDispatcher

  // create mattermost client
  val mmc: MattermostClient = MattermostClient.builder()
    .url(mmUrl)
    .logLevel(Level.INFO)
    .ignoreUnknownProperties()
    .build()
  mmc.setAccessToken(botToken)

  // handle receive-loop via Sink
  private val sink: Sink[Message, NotUsed] = Flow[Message]
    .map {m =>
      Try {
        m match {
          case m: TextMessage.Strict =>
            system.log.debug(s"Got message: ${m.text}")
            m.text.parseJson.asJsObject match {
              case ast if ast.fields.contains("event") => ast.convertTo[EventMessage]
              case ast if ast.fields.contains("status") => ast.convertTo[StatusMessage]
              case _ => throw new MatterMostWsProtocolException("Unknown message type:\n" + m.text.parseJson.prettyPrint)
            }
          case other =>
            throw new MatterMostWsProtocolException(s"Unsupported message format: $other")
        }
      }
    }
    .to(Sink.foreachAsync[Try[MattermostMessage]](parallelism = 10) { t =>
      Future(t match {
        case Success(msg) =>
          // run all hooks until the first hook consumes the message
          hooks.exists(_(msg).consumed)
        case Failure(ex) =>
          system.log.error(ex, "Could not handle Websocket message.")
      })
    })


  // start & connect websocket service
  private val serviceActor: ActorRef[ServiceActorCommand] = system.spawn(ServiceActor(),s"ServiceActor-${java.util.UUID.randomUUID()}")

  private val hooks = mutable.Buffer.empty[Hook]

  // register hooks
  def withHook(hook: Hook): Bot = {
    hooks.append(hook)
    this
  }

  def connect(): Bot = {
    serviceActor ! Connect(wsUrl, botToken, sink)
    this
  }

  def disconnect(): Bot = {
    serviceActor ! Disconnect()
    this
  }
}
