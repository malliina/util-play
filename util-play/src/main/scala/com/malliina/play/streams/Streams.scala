package com.malliina.play.streams

import java.io._
import java.nio.file.Path

import akka.NotUsed
import akka.stream.scaladsl._
import akka.util.ByteString
import com.malliina.storage.{StorageInt, StorageSize}
import play.api.libs.iteratee.{Cont, Done, Input, Iteratee}

import scala.concurrent.{Future, ExecutionContext}

trait Streams {
  //  /**
  //   * http://stackoverflow.com/questions/12066993/uploading-file-as-stream-in-play-framework-2-0
  //   *
  //   * @return an [[InputStream]] and an [[Iteratee]] such that any bytes consumed by the Iteratee are made available to the InputStream
  //   */
  //  def joinedStream()(implicit ec: ExecutionContext): (InputStream, Iteratee[Array[Byte], OutputStream]) = {
  //    val outStream = new PipedOutputStream()
  //    val inStream = new PipedInputStream(outStream)
  //    val iteratee = fromOutputStream(outStream).map(os => {
  //      os.close()
  //      os
  //    })
  //    (inStream, iteratee)
  //  }
  /**
    * http://stackoverflow.com/questions/12066993/uploading-file-as-stream-in-play-framework-2-0
    *
    * @return an [[InputStream]] and an [[Iteratee]] such that any bytes consumed by the Iteratee are made available to the InputStream
    */
  def joinedStream(inputBuffer: StorageSize = 10.megs)(implicit ec: ExecutionContext): (PipedInputStream, Sink[ByteString, Future[Long]]) = {
    val outStream = new PipedOutputStream()
    val bufferSize = math.min(inputBuffer.toBytes.toInt, Int.MaxValue)
    val inStream = new PipedInputStream(outStream, bufferSize)
    val iteratee = closingStreamWriter(outStream)
    (inStream, iteratee)
  }

  /** Builds a [[Sink]] that writes consumed bytes to all `outStreams`. The streams are closed when the [[Sink]] is done.
    *
    * @return a [[Sink]] that writes to `outStream`
    */
  def closingStreamWriter(outStreams: OutputStream*)(implicit ec: ExecutionContext): Sink[ByteString, Future[Long]] = {
    streamWriter(outStreams: _*).mapMaterializedValue(_.map { bytes =>
      outStreams.foreach(_.close())
      bytes
    })
  }

  /**
    * @return an [[Iteratee]] that writes any consumed bytes to `os`
    */
  def streamWriter(outStreams: OutputStream*)(implicit ec: ExecutionContext): Sink[ByteString, Future[Long]] =
    byteConsumer(bytes => {
      outStreams.foreach(_.write(bytes.asByteBuffer.array()))
    })
  /**
    * @param f
    * @return an iteratee that consumes bytes by applying `f` and returns the total number of bytes consumed
    */
  def byteConsumer(f: ByteString => Unit)(implicit ec: ExecutionContext): Sink[ByteString, Future[Long]] =
    Sink.fold[Long, ByteString](0)((count, bytes) => {
      f(bytes)
      count + bytes.length
    })

  //  def byteConsumer(f: Array[Byte] => Unit)(implicit ec: ExecutionContext): Iteratee[Array[Byte], Long] =
  //    Iteratee.fold[Array[Byte], Long](0)((count, bytes) => {
  //      f(bytes)
  //      count + bytes.length
  //    })

  /**
    * @return an [[Iteratee]] that writes any consumed bytes to `os`
    */
  def fromOutputStream(os: OutputStream)(implicit ec: ExecutionContext) =
    Sink.fold[OutputStream, ByteString](os)((state, bytes) => {
      state.write(bytes.asByteBuffer.array())
      state
    })

  //  def fromOutputStream(os: OutputStream)(implicit ec: ExecutionContext) =
  //    Iteratee.fold[Array[Byte], OutputStream](os)((state, data) => {
  //      state.write(data)
  //      state
  //    })

  /**
    * @param file destination file
    * @return a [[Sink]] that writes bytes to `file`, keeping track of the number of bytes written
    */
  def fileWriter2(file: Path) = FileIO.toFile(file.toFile)
}

object Streams extends Streams
