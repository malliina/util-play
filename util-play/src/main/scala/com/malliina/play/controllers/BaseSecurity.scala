package com.malliina.play.controllers

import java.nio.file.{Files, Path, Paths}

import akka.stream.Materializer
import com.malliina.play.auth.{Auth, BasicCredentials}
import com.malliina.play.controllers.BaseSecurity.log
import com.malliina.play.http.{AuthRequest, AuthResult}
import play.api.Logger
import play.api.libs.streams.Accumulator
import play.api.libs.{Files => PlayFiles}
import play.api.mvc.Results._
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait BaseSecurity {

  implicit def mat: Materializer

  def authenticateFromSession(implicit request: RequestHeader): Future[Option[String]] =
    fut(request.session.get(Security.username))

  /** Basic HTTP authentication.
    *
    * The "Authorization" request header should be like: "Basic base64(username:password)", where
    * base64(x) means x base64-encoded.
    *
    * @param request request from which the Authorization header is validated
    * @return the username wrapped in an Option if successfully authenticated, None otherwise
    */
  def authenticateFromHeader(implicit request: RequestHeader): Future[Option[String]] =
    performAuthentication(Auth.basicCredentials(request))

  /** Authenticates based on the "u" and "p" query string parameters.
    *
    * @param request request
    * @return the username, if successfully authenticated
    */
  def authenticateFromQueryString(implicit request: RequestHeader): Future[Option[String]] =
    performAuthentication(Auth.credentialsFromQuery(request))

  def performAuthentication(creds: Option[BasicCredentials]) =
    creds.map(validateUser).getOrElse(fut(None))

  def validateUser(creds: BasicCredentials): Future[Option[String]] =
    validateCredentials(creds).map(isValid => if (isValid) Option(creds.username) else None)

  /** Override if you intend to use password authentication.
    *
    * @return True if the credentials are valid; false otherwise. False by default.
    */
  def validateCredentials(creds: BasicCredentials): Future[Boolean] = fut(false)

  /** Retrieves the authenticated username from the request.
    *
    * Attempts to read the "username" session variable, but if no such thing exists,
    * attempts to authenticate based on the the HTTP Authorization header,
    * finally if that also fails, authenticates based on credentials in the query string.
    *
    * @return the authentication result wrapped in an [[scala.Option]] if successfully authenticated, [[scala.None]] otherwise
    */
  def authenticate(implicit request: RequestHeader): Future[Option[AuthResult]] = {
    import com.malliina.play.concurrent.FutureOps2
    authenticateFromSession
      .checkOrElse(_.nonEmpty, authenticateFromHeader)
      .checkOrElse(_.nonEmpty, authenticateFromQueryString)
      .map(_.map(lift))
  }

  def checkOrElse[T, U >: T](f: Future[T], orElse: => Future[U], check: T => Boolean): Future[U] =
    f.flatMap(t => if (check(t)) fut(t) else orElse)

  /** Called when an unauthorized request has been made. Also
    * called when a failed authentication attempt is made.
    *
    * Returns HTTP 401 by default; override to handle unauthorized
    * requests in a more app-specific manner.
    *
    * @param req header of request which failed authentication
    * @return "auth failed" result
    */
  protected def onUnauthorized(implicit req: RequestHeader): Result = {
    val ip = req.remoteAddress
    val resource = req.path
    log warn s"Unauthorized request to: $resource from: $ip"
    Unauthorized
  }

  def LoggedSecureAction[U](authFunction: RequestHeader => Option[U])(authAction: U => EssentialAction): EssentialAction =
    Security.Authenticated(req => authFunction(req), req => onUnauthorized(req))(user => Logged(authAction(user)))

  def AuthActionAsync(f: AuthRequest[AnyContent] => Future[Result]) =
    AuthenticatedLogged(user => Action.async(req => f(new AuthRequest(user.user, req, user.cookie))))

  def LoggedSecureActionAsync[U](authFunction: RequestHeader => Future[U])(authAction: U => EssentialAction) =
    authenticatedAsync2(authFunction, req => onUnauthorized(req))(user => Logged(authAction(user)))

  def AuthAction(f: AuthRequest[AnyContent] => Result) =
    AuthenticatedLogged(user => Action(req => f(new AuthRequest(user.user, req, user.cookie))))

  def AuthenticatedLogged(f: AuthResult => EssentialAction): EssentialAction = Authenticated(user => Logged(user, f))

  def AuthenticatedLogged(f: => EssentialAction): EssentialAction = AuthenticatedLogged(_ => f)

  def Authenticated(f: => EssentialAction): EssentialAction = Authenticated(user => f)

  def Authenticated(f: AuthResult => EssentialAction): EssentialAction =
    authenticatedAsync(req => authenticate(req), unAuthorizedRequest => onUnauthorized(unAuthorizedRequest))(f)

  /** Logs authenticated requests.
    */
  def Logged(user: AuthResult, f: AuthResult => EssentialAction) =
    EssentialAction(request => {
      val qString = request.rawQueryString
      // removes query string from logged line if it contains a password, assumes password is in 'p' parameter
      def queryString =
        if (qString != null && qString.length > 0 && !qString.contains("p=")) s"?$qString"
        else ""
      log info s"User: ${user.user} from: ${request.remoteAddress} requests: ${request.path}$queryString"
      f(user)(request)
    })

  def Logged(action: EssentialAction): EssentialAction = EssentialAction(req => {
    log debug s"Request: ${req.path} from: ${req.remoteAddress}"
    action(req)
  })

  /**
    * @param authFunction authentication that fails the Future if authentication fails
    * @return an authenticated action
    */
  def authenticatedAsync2[A](authFunction: RequestHeader => Future[A],
                             onUnauthorized: RequestHeader => Result)(action: A => EssentialAction): EssentialAction = {
    import com.malliina.concurrent.FutureOps
    val f2: RequestHeader => Future[Option[A]] = req => authFunction(req).map(a => Some(a)).recoverAll(_ => None)
    authenticatedAsync(f2, onUnauthorized)(action)
  }

  /** Async version of Security.Authenticated.
    *
    * @param auth           auth function
    * @param onUnauthorized callback if auth fails
    * @param action         authenticated action
    * @tparam A type of user+request
    * @return an authenticated action
    */
  def authenticatedAsync[A](auth: RequestHeader => Future[Option[A]],
                            onUnauthorized: RequestHeader => Result)(action: A => EssentialAction): EssentialAction =
    EssentialAction { request =>
      val futureAccumulator = auth(request) map { maybeUser =>
        maybeUser
          .map(user => action(user).apply(request))
          .getOrElse(Accumulator.done(onUnauthorized(request)))
      }
      Accumulator.flatten(futureAccumulator)
    }

  val uploadDir = Paths get sys.props("java.io.tmpdir")

  protected def saveFiles(request: Request[MultipartFormData[PlayFiles.TemporaryFile]]): Seq[Path] =
    request.body.files.map(file => {
      val dest = uploadDir resolve file.filename
      if (!Files.exists(dest))
        file.ref.moveTo(dest.toFile, replace = true)
      dest
    })

  protected def lift(user: String) = AuthResult(user)

  private def fut[T](t: T): Future[T] = Future.successful(t)
}

object BaseSecurity {
  private val log = Logger(getClass)
}
