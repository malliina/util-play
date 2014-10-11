package com.mle.play.auth

/**
 * @author Michael
 */
case class UnAuthToken(user: String, series: Long, token: Long) {
  lazy val isEmpty = this == UnAuthToken.empty
}

object UnAuthToken {
  val empty = UnAuthToken("", 0, 0)
}