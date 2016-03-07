package com.malliina.play.auth

import com.malliina.util.Log
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

trait TokenLogging extends TokenStore with Log {
  abstract override def persist(token: Token): Future[Unit] = {
    super.persist(token).map(_ => {
      log debug s"Persisted token: $token"
    })
  }


  abstract override def remove(token: Token): Future[Unit] = {
    super.remove(token).map(_ => {
      log debug s"Removed token: $token"
    })
  }
}