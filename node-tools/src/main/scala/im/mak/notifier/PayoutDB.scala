package im.mak.notifier

import com.google.common.io.ByteStreams
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.{Asset, TransactionParsers}
import com.wavesplatform.utils.ScorexLogging

object PayoutDB extends ScorexLogging {
  type LeasesSnapshot = Seq[LeaseTransaction]
  object LeasesSnapshot {
    def toBytes(transactions: LeasesSnapshot): Array[Byte] = {
      val dataOutput = ByteStreams.newDataOutput()
      dataOutput.writeInt(transactions.length)
      transactions.foreach { tx =>
        val txBytes = tx.bytes()
        dataOutput.writeInt(txBytes.length)
        dataOutput.write(txBytes)
      }
      dataOutput.toByteArray
    }

    def fromBytes(bytes: Array[Byte]): LeasesSnapshot = {
      val dataInput = ByteStreams.newDataInput(bytes)
      val length    = dataInput.readInt()
      for (_ <- 1 to length) yield {
        val txLength = dataInput.readInt()
        val txBytes  = new Array[Byte](txLength)
        dataInput.readFully(txBytes)
        TransactionParsers.parseBytes(txBytes).asInstanceOf[LeaseTransaction]
      }
    }
  }

  case class MinedBlock(height: Int, reward: Long)

  case class Payout(
      id: Int,
      fromHeight: Int,
      toHeight: Int,
      amount: Long,
      assetId: Option[String],
      generatingBalance: Long,
      activeLeases: Array[Byte],
      txId: Option[String],
      txHeight: Option[Int],
      confirmed: Boolean
  ) {
    lazy val activeLeasesDecoded: LeasesSnapshot = LeasesSnapshot.fromBytes(activeLeases)
  }

  import io.getquill._

  private[this] lazy val ctx = new H2JdbcContext(SnakeCase, "miner-notifier.db.ctx")

  import ctx.{lift => liftQ, _}

  private[this] implicit val minedBlocksMeta = schemaMeta[MinedBlock]("mined_blocks")
  private[this] implicit val payoutsMeta     = schemaMeta[Payout]("payouts")

  def addMinedBlock(height: Index, reward: Long): Unit = {
    val existing = {
      val q = quote(query[MinedBlock].filter(_.height == liftQ(height)))
      run(q).headOption
    }

    val q = if (existing.nonEmpty) quote {
      query[MinedBlock].insert(_.height -> liftQ(height), _.reward -> liftQ(reward))
    } else
      quote {
        query[MinedBlock]
          .filter(_.height == liftQ(height))
          .update(v => v.reward -> (v.reward + liftQ(reward)))
      }
    log.info(
      s"Block at $height reward is ${Format.waves(existing.fold(0L)(_.reward) + reward)} Waves"
    )
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

  def unconfirmedPayouts(): Seq[Payout] = {
    val q = quote {
      query[Payout].filter(_.confirmed == false)
    }
    run(q)
  }

  def addPayout(
      fromHeight: Int,
      toHeight: Int,
      amount: Long,
      assetId: Option[String],
      generatingBalance: Long,
      activeLeases: Seq[LeaseTransaction]
  ): Int = {
    val snapshotBytes = LeasesSnapshot.toBytes(activeLeases)
    val q = quote {
      query[Payout]
        .insert(
          _.fromHeight        -> liftQ(fromHeight),
          _.toHeight          -> liftQ(toHeight),
          _.amount            -> liftQ(amount),
          _.assetId           -> liftQ(assetId),
          _.generatingBalance -> liftQ(generatingBalance),
          _.activeLeases      -> liftQ(snapshotBytes)
        )
        .returning(_.id)
    }
    val id = run(q)
    log.info(s"Payout registered: #$id ($fromHeight - $toHeight, $amount of ${Asset.fromString(assetId)}, ${activeLeases.length} leases)")
    id
  }

  def setPayoutTxId(id: Int, txId: String, txHeight: Int): Unit = {
    val q = quote {
      query[Payout].filter(_.id == liftQ(id)).update(_.txId -> Some(liftQ(txId)), _.txHeight -> Some(liftQ(txHeight)))
    }
    run(q)
    log.info(s"Payout #$id transaction id set to $txId at $txHeight")
  }

  def confirmPayout(id: Int, height: Int): Unit = {
    val q = quote {
      query[Payout].filter(_.id == liftQ(id)).update(_.confirmed -> true, _.txHeight -> Some(liftQ(height)))
    }
    run(q)
    log.info(s"Payout confirmed: #$id")
  }

  def processRollback(height: Int): Unit = {
    val removeBlocks = quote {
      query[MinedBlock].filter(_.height > liftQ(height)).delete
    }

    val removeInvPayouts = quote {
      query[Payout].filter(p => p.toHeight > liftQ(height) && (p.txHeight.isEmpty || p.txHeight.exists(_ > liftQ(height)))).delete
    }

    val resetPayoutTxs = quote {
      query[Payout].filter(p => p.txHeight.exists(_ > liftQ(height))).update(_.txHeight -> None, _.confirmed -> false)
    }

    ctx.transaction {
      run(removeBlocks)
      run(removeInvPayouts)
      run(resetPayoutTxs)
    }
  }
}
