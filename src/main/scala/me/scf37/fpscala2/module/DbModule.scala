package me.scf37.fpscala2.module

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

import cats.Monad
import cats.effect.Async
import cats.effect.ContextShift
import cats.implicits._
import javax.sql.DataSource
import me.scf37.fpscala2.db.TxManager
import me.scf37.fpscala2.db.sql.SqlEffectEval
import me.scf37.fpscala2.db.sql.SqlTxManager
import me.scf37.fpscala2.module.config.DbConfig
import org.apache.commons.dbcp2.DriverManagerConnectionFactory
import org.apache.commons.dbcp2.PoolableConnectionFactory
import org.apache.commons.dbcp2.PoolingDataSource
import org.apache.commons.pool2.impl.GenericObjectPool
import org.flywaydb.core.Flyway

import scala.concurrent.ExecutionContext

trait DbModule[I[_], F[_], DbEffect[_]] {
  def tx: I[TxManager[F, DbEffect]]
}

object DbModule {

  def apply[I[_]: Later: Monad, F[_]: Async, DbEffect[_]](
    config: DbConfig,
    alwaysRollback: Boolean = false
  )(implicit DE: SqlEffectEval[F, DbEffect]
  ): DbModule[I, F, DbEffect] = new DbModule[I, F, DbEffect] {

    private val jdbcPool: I[ContextShift[F]] = Later[I].later {
      val jdbcPool = Executors.newFixedThreadPool(config.maxPoolSize, new ThreadFactory {
        private val id = new AtomicInteger()
        override def newThread(r: Runnable): Thread = {
          val t = new Thread(r)
          t.setDaemon(true)
          t.setName("jdbc-pool-" + id.incrementAndGet())
          t
        }
      })

      new ContextShift[F] {
        override def shift: F[Unit] =
          Async[F].async(cb => {
            jdbcPool.submit(() => {
              cb(Right(()))
            }.asInstanceOf[Runnable])
          })

        override def evalOn[A](ec: ExecutionContext)(fa: F[A]): F[A] = ???
      }
    }

    private val flyway: I[Unit] = Later[I].later {
      val flyway = Flyway.configure()
        .dataSource(config.url, config.user, config.password)
        .baselineOnMigrate(true)
        .load()

      // Start the migration
      flyway.migrate()
    }

    private val dataSource: I[DataSource] = Later[I].later {
      val connectionFactory = new DriverManagerConnectionFactory(config.url, config.user, config.password)

      val poolableConnectionFactory = new PoolableConnectionFactory(connectionFactory, null)

      val connectionPool = new GenericObjectPool(poolableConnectionFactory)

      connectionPool.setMinIdle(config.minPoolSize)
      connectionPool.setMaxTotal(config.maxPoolSize)

      poolableConnectionFactory.setPool(connectionPool)

      new PoolingDataSource(connectionPool)
    }

    override val tx: I[TxManager[F, DbEffect]] = for {
      _ <- flyway
      pool <- jdbcPool
      dataSource <- dataSource
    } yield  {
      new SqlTxManager[F, DbEffect](dataSource, pool, alwaysRollback)
    }
  }
}
