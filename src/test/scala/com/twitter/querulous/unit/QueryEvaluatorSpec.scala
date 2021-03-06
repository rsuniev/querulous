package com.twitter.querulous.unit

import java.sql.{Connection, SQLException, DriverManager}
import scala.collection.mutable
import net.lag.configgy.Configgy
import com.mysql.jdbc.exceptions.MySQLIntegrityConstraintViolationException
import com.twitter.querulous.connectionpool.ConnectionPool
import com.twitter.querulous.evaluator.{StandardQueryEvaluator, QueryEvaluator}
import com.twitter.querulous.query.{QueryFactory, SqlQueryFactory}
import com.twitter.xrayspecs.Time
import com.twitter.xrayspecs.TimeConversions._
import org.specs.Specification
import org.specs.mock.JMocker

object QueryEvaluatorSpec extends Specification with JMocker {
  val config = Configgy.config.configMap("db")
  val username = config("username")
  val password = config("password")

  "QueryEvaluator" should {
    val queryEvaluator = QueryEvaluator("localhost", "db_test", username, password)
    val rootQueryEvaluator = QueryEvaluator("localhost", null, username, password)
    val queryFactory = new SqlQueryFactory

    doBefore {
      rootQueryEvaluator.execute("CREATE DATABASE IF NOT EXISTS db_test")
    }

    doAfter {
      queryEvaluator.execute("DROP TABLE IF EXISTS foo")
    }

    "connection pooling" in {
      "transactionally" >> {
        val connection = mock[Connection]
        val connectionPool = mock[ConnectionPool]
        val queryEvaluator = new StandardQueryEvaluator(connectionPool, queryFactory)

        expect {
          one(connectionPool).reserve() willReturn connection
          one(connection).setAutoCommit(false)
          one(connection).prepareStatement("SELECT 1")
          one(connection).commit()
          one(connection).setAutoCommit(true)
          one(connectionPool).release(connection)
        }

        queryEvaluator.transaction { transaction =>
          transaction.selectOne("SELECT 1") { _.getInt("1") }
        }
      }

      "nontransactionally" >> {
        val connection = mock[Connection]
        val connectionPool = mock[ConnectionPool]
        val queryEvaluator = new StandardQueryEvaluator(connectionPool, queryFactory)

        expect {
          one(connectionPool).reserve() willReturn connection
          one(connection).prepareStatement("SELECT 1")
          one(connectionPool).release(connection)
        }

        var list = new mutable.ListBuffer[Int]
        queryEvaluator.selectOne("SELECT 1") { _.getInt("1") }
      }
    }

    "select rows" in {
      var list = new mutable.ListBuffer[Int]
      queryEvaluator.select("SELECT 1 as one") { resultSet =>
        list += resultSet.getInt("one")
      }
      list.toList mustEqual List(1)
    }

    "fallback to a read slave" in {
      // should always succeed if you have the right mysql driver.
      val queryEvaluator = QueryEvaluator(List("localhost:12349", "localhost"), "db_test", username, password)
      queryEvaluator.selectOne("SELECT 1") { row => row.getInt(1) }.toList mustEqual List(1)
      queryEvaluator.execute("CREATE TABLE foo (id INT)") must throwA[SQLException]
    }

    "transaction" in {
      "when there is an exception" >> {
        queryEvaluator.execute("CREATE TABLE foo (bar INT) TYPE=INNODB")

        try {
          queryEvaluator.transaction { transaction =>
            transaction.execute("INSERT INTO foo VALUES (1)")
            throw new Exception("oh noes")
          }
        } catch {
          case _ =>
        }

        queryEvaluator.select("SELECT * FROM foo")(_.getInt("bar")).toList mustEqual Nil
      }

      "when there is not an exception" >> {
        queryEvaluator.execute("CREATE TABLE foo (bar VARCHAR(50), baz INT) TYPE=INNODB")

        queryEvaluator.transaction { transaction =>
          transaction.execute("INSERT INTO foo VALUES (?, ?)", "one", 2)
        }

        queryEvaluator.select("SELECT * FROM foo") { row => (row.getString("bar"), row.getInt("baz")) }.toList mustEqual List(("one", 2))
      }
    }

  }
}
