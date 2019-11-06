package im.mak.nodetools

import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.transaction.{Transaction, TransactionParsers}
import com.wavesplatform.utils.ScorexLogging

//noinspection TypeAnnotation
object PayoutDB extends ScorexLogging {
  import io.getquill.{MappedEncoding, _}
  lazy val ctx = new H2JdbcContext(SnakeCase, "node-tools.db.ctx")
  import ctx.{lift => liftQ, _}

  implicit def transactionEncoding[T <: Transaction]: MappedEncoding[T, Array[Byte]] =
    MappedEncoding[T, Array[Byte]](_.bytes())
  implicit def transactionDecoding[T <: Transaction]: MappedEncoding[Array[Byte], T] =
    MappedEncoding[Array[Byte], T](data => TransactionParsers.parseBytes(data).get.asInstanceOf[T])

  case class MinedBlock(height: Int, reward: Long)

  case class Payout(
      id: Int,
      fromHeight: Int,
      toHeight: Int,
      amount: Long,
      generatingBalance: Long
  )

  case class PayoutTransaction(id: String, payoutId: Int, transaction: MassTransferTransaction, height: Option[Int])
  case class Lease(id: String, transaction: LeaseTransaction, height: Int)
  case class PayoutLease(id: Int, leaseId: String)
  case class StateVersion(key: String, version: Int)

  private[this] implicit val minedBlocksMeta        = schemaMeta[MinedBlock]("mined_blocks")
  private[this] implicit val payoutsMeta            = schemaMeta[Payout]("payouts")
  private[this] implicit val payoutTransactionsMeta = schemaMeta[PayoutTransaction]("payout_transactions")
  private[this] implicit val leasesMeta             = schemaMeta[Lease]("leases")
  private[this] implicit val payoutLeasesMeta       = schemaMeta[PayoutLease]("payout_leases")
  private[this] implicit val stateVersionMeta       = schemaMeta[StateVersion]("state_version")

  def addMinedBlock(height: Index, reward: Long): Unit = {
    val exists = quote(query[MinedBlock].filter(_.height == liftQ(height)).nonEmpty)

    val insert = quote {
      query[MinedBlock].insert(_.height -> liftQ(height), _.reward -> liftQ(reward))
    }

    val update = quote {
      query[MinedBlock]
        .filter(_.height == liftQ(height))
        .update(_.reward -> liftQ(reward))
    }

    transaction {
      if (run(exists)) run(update)
      else run(insert)
    }
  }

  def lastRegisteredHeight(): Option[Int] = {
    val q = quote {
      query[MinedBlock].map(_.height).max
    }
    run(q)
  }

  def calculateReward(fromHeight: Int, toHeight: Int): Long = {
    val q      = quote(query[MinedBlock].filter(v => v.height >= liftQ(fromHeight) && v.height <= liftQ(toHeight)))
    val blocks = run(q)
    blocks.map(_.reward).sum
  }

  def lastPayoutHeight(): Int = {
    val q = quote {
      query[Payout].map(_.toHeight).max
    }
    run(q).getOrElse(0)
  }

  def unconfirmedTransactions(height: Int, delay: Int): Seq[PayoutTransaction] = {
    val q = quote {
      query[PayoutTransaction]
        .join(query[Payout])
        .on((t, p) => t.payoutId == p.id)
        .filter { case (t, p) => p.toHeight <= liftQ(height - delay) && (t.height.isEmpty || t.height.exists(_ < liftQ(height + delay))) }
        .map(_._1)
    }
    run(q)
  }

  def addPayout(
      fromHeight: Int,
      toHeight: Int,
      amount: Long,
      generatingBalance: Long,
      activeLeases: Seq[(Int, LeaseTransaction)]
  ): Payout = {
    require(amount > 0, s"Payout amount must be positive. Actual: $amount")
    require(fromHeight <= toHeight, s"End height ($toHeight) of the interval can't be earlier than start ($fromHeight)")
    require(generatingBalance >= 1000, s"Generating balance can't be less than 1000 Waves. Actual: $generatingBalance")

    def insertLease(height: Int, tx: LeaseTransaction): Unit = {
      val txId = tx.id().toString

      val existsQ = quote(query[Lease].filter(_.id == liftQ(txId)).nonEmpty)
      val exists  = run(existsQ)

      val insert = quote(query[Lease].insert(_.id -> liftQ(txId), _.height -> liftQ(height), _.transaction -> liftQ(tx)))
      if (!exists) run(insert)
    }

    val insertPayout = quote {
      query[Payout]
        .insert(
          _.fromHeight        -> liftQ(fromHeight),
          _.toHeight          -> liftQ(toHeight),
          _.amount            -> liftQ(amount),
          _.generatingBalance -> liftQ(generatingBalance)
        )
        .returning(_.id)
    }

    def insertPayoutLeases(id: Int, leases: Seq[String]) = quote {
      liftQuery(leases).foreach(lease => query[PayoutLease].insert(_.id -> liftQ(id), _.leaseId -> lease))
    }

    val payoutId = transaction {
      activeLeases.foreach { case (height, tx) => insertLease(height, tx) }

      val id = run(insertPayout)
      run(insertPayoutLeases(id, activeLeases.map(_._2.id().toString)))
      id
    }
    log.info(s"Payout [$fromHeight-$toHeight] registered with id #$payoutId: ${Format.waves(amount)} Waves, ${activeLeases.length} leases)")
    Payout(payoutId, fromHeight, toHeight, amount, generatingBalance)
  }

  def addPayoutTransactions(payoutId: Int, transactions: Seq[MassTransferTransaction]): Unit = {
    val q = quote {
      liftQuery(transactions.map(tx => (tx.id().toString, tx))).foreach {
        case (id, tx) => query[PayoutTransaction].insert(_.id -> id, _.payoutId -> liftQ(payoutId), _.transaction -> tx)
      }
    }
    run(q)
  }

  def confirmTransaction(id: String, height: Int): Unit = {
    val q = quote {
      query[PayoutTransaction].filter(_.id == liftQ(id)).update(_.height -> Some(liftQ(height)))
    }
    run(q)
  }

  def processRollback(height: Int): Unit = {
    val removeBlocks = quote {
      query[MinedBlock].filter(_.height > liftQ(height)).delete
    }

    val resetPayoutTxs = quote {
      query[PayoutTransaction].filter(p => p.height.exists(_ > liftQ(height))).update(_.height -> None)
    }

    val removeInvPayouts = quote {
      query[Payout].filter { p =>
        val txs = query[PayoutTransaction].filter(t => t.payoutId == p.id && t.height.nonEmpty)
        p.toHeight > liftQ(height) && txs.isEmpty
      }.delete
    }

    ctx.transaction {
      run(removeBlocks)
      run(resetPayoutTxs)
      run(removeInvPayouts)
    }
  }

  def getVersion(key: String): Option[Int] = {
    val q = quote(query[StateVersion].filter(_.key == liftQ(key)).map(_.version))
    run(q).headOption
  }

  def setVersion(key: String, value: Int): Unit = {
    val q = quote(query[StateVersion].filter(_.key == liftQ(key)).update(_.version -> liftQ(value)))
    run(q)
  }
}
