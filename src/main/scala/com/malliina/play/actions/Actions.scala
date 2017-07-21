package com.malliina.play.actions

import akka.actor.ActorSystem
import com.malliina.play.concurrent.ExecutionContexts
import play.api.mvc._

import scala.concurrent.Future

trait Actions {

  /**
    * Executes the work on a large thread pool suitable for synchronous IO.
    */
  abstract class SyncAction(actorSystem: ActorSystem) extends DefaultActionBuilder[AnyContent] {
    override protected val executionContext = new ExecutionContexts(actorSystem).synchronousIO
  }

  /**
    * Default action builder, override what you need.
    */
  abstract class DefaultActionBuilder[B] extends ActionBuilder[Request, B] {
    def invokeBlock[A](request: Request[A], block: (Request[A]) => Future[Result]): Future[Result] =
      block(request)
  }

}

object Actions extends Actions
