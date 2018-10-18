package com.malliina.play.auth

import java.math.BigInteger
import java.security.SecureRandom

import com.malliina.http.FullUrl
import com.malliina.play.auth.CodeValidator._
import com.malliina.play.http.FullUrls
import play.api.Logger
import play.api.libs.json.Reads
import play.api.mvc.Results.Redirect
import play.api.mvc.{Call, RequestHeader, Result}

import scala.concurrent.Future

object CodeValidator {
  private val log = Logger(getClass)

  val AuthorizationCode = "authorization_code"
  val ClientId = "client_id"
  val ClientSecret = "client_secret"
  val CodeKey = "code"
  val EmailKey = "email"
  val GrantType = "grant_type"
  val IdTokenKey = "id_token"
  val LoginHint = "login_hint"
  val Nonce = "nonce"
  val RedirectUri = "redirect_uri"
  val ResponseType = "response_type"
  val Scope = "scope"
  val State = "state"

  val scope = "openid email"

  private val rng = new SecureRandom()

  def randomString(): String = new BigInteger(130, rng).toString(32)
}

/**
  *
  * @tparam U type of user object, e.g. Username, Email, AppUser, String
  */
trait CodeValidator[U, V] extends AuthValidator with OAuthValidator[V] {
  def validate(code: Code, req: RequestHeader): Future[Either[AuthError, U]]

  def onOutcome(outcome: Either[AuthError, U], req: RequestHeader): Result

  override def validateCallback(req: RequestHeader): Future[Result] = {
    val requestState = req.getQueryString(State)
    val sessionState = req.session.get(State)
    val isStateOk = requestState.exists(rs => sessionState.contains(rs))
    if (isStateOk) {
      req.getQueryString(CodeKey).map { code =>
        validate(Code(code), req).map { outcome =>
          onOutcome(outcome, req)
        }
      }.getOrElse {
        log.error(s"Authentication failed, code missing. $req")
        fut(onOutcome(Left(OAuthError("Code missing.")), req))
      }
    } else {
      val detailed = (requestState, sessionState) match {
        case (Some(rs), Some(ss)) => s"Got '$rs', expected '$ss'."
        case (Some(rs), None) => s"Got '$rs', but found nothing to compare to."
        case (None, Some(ss)) => s"No state in request, expected '$ss'."
        case _ => "No state in request and nothing to compare to either."
      }
      log.error(s"Authentication failed, state mismatch. $detailed $req")
      fut(onOutcome(Left(OAuthError("State mismatch.")), req))
    }
  }

  def redirResult(authorizationEndpoint: FullUrl,
                  authParams: Map[String, String],
                  nonce: Option[String] = None): Result = {
    val state = randomString()
    val encodedParams = (authParams ++ Map(State -> state)).mapValues(urlEncode)
    val url = authorizationEndpoint.append(s"?${stringify(encodedParams)}")
    val sessionParams = Seq(State -> state) ++ nonce.map(n => Seq(Nonce -> n)).getOrElse(Nil)
    Redirect(url.url).withSession(sessionParams: _*)
  }

  protected def urlEncode(s: String) = AuthValidator.urlEncode(s)

  protected def randomString(): String = CodeValidator.randomString()

  protected def redirUrl(call: Call, rh: RequestHeader) = urlEncode(FullUrls(call, rh).url)

  protected def commonAuthParams(authScope: String, rh: RequestHeader): Map[String, String] = Map(
    RedirectUri -> FullUrls(redirCall, rh).url,
    ClientId -> clientConf.clientId,
    Scope -> authScope
  )

  /** Not encoded.
    */
  protected def validationParams(code: Code, req: RequestHeader): Map[String, String] = Map(
    ClientId -> clientConf.clientId,
    ClientSecret -> clientConf.clientSecret,
    RedirectUri -> FullUrls(redirCall, req).url,
    CodeKey -> code.code
  )

  def postForm[T: Reads](url: FullUrl, params: Map[String, String]) =
    http.postFormAs[T](url, params).map(_.left.map(e => OkError(e)))

  def postEmpty[T: Reads](url: FullUrl,
                          headers: Map[String, String],
                          params: Map[String, String]) =
    http.postFormAs[T](url, params, headers).map(_.left.map(e => OkError(e)))

  def getJson[T: Reads](url: FullUrl) = http.getAs[T](url).map(_.left.map(e => OkError(e)))
}
