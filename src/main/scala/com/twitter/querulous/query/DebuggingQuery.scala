package com.twitter.querulous.query

import java.sql.{Connection, Timestamp}

class DebuggingQueryFactory(queryFactory: QueryFactory, log: String => Unit) extends QueryFactory {
  def apply(connection: Connection, query: String, params: Any*) = {
    new DebuggingQuery(queryFactory(connection, query, params: _*), log, query, params)
  }
}

class DebuggingQuery(query: Query, log: String => Unit, queryString: String, params: Seq[Any])
  extends QueryProxy(query) {

  def makeDebugString(param: Any): String = {
    param match {
      case s: String =>
        "\""+s+"\""
      case c: Char =>
        "'"+c+"'"
      case l: Long =>
        l.toString
      case i: Int =>
        i.toString
      case b: Array[Byte] =>
        "(" + b.size + " bytes)"
      case b: Boolean =>
        b.toString
      case d: Double =>
        d.toString
      case t: Timestamp =>
        t.toString
      case is: Seq[_] =>
        is.map(makeDebugString).mkString("(", ", ", ")")
      case _ =>
        "Unknown argument type."
    }
  }

  override def delegate[A](f: => A) = {
    log(queryString + " " + params.map(makeDebugString).mkString("(", ", ", ")"))
    f
  }
}
