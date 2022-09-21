package services

import com.google.inject.{ImplementedBy, Provider, Singleton}
import org.bitcoinj.core.{AbstractBlockChain, BlockChain, PeerGroup}
import org.bitcoinj.params.{AbstractBitcoinNetParams, MainNetParams}
import org.bitcoinj.store.PostgresFullPrunedBlockStore
import play.api.Configuration

import javax.inject.Inject

@ImplementedBy(classOf[MainNetParamsProvider])
trait NetParamsProvider extends Provider[AbstractBitcoinNetParams] {
  val get: AbstractBitcoinNetParams
}

@ImplementedBy(classOf[PostgresBlockChain])
trait BlockChainProvider extends Provider[AbstractBlockChain] {
  val get: AbstractBlockChain
}

@ImplementedBy(classOf[BlockChainPeerGroup])
trait PeerGroupProvider extends Provider[PeerGroup] {
  val get: PeerGroup
}

@ImplementedBy(classOf[PlayBlockChainDatabaseConfiguration])
trait BlockChainDatabaseConfiguration {
  def serverName: String
  def portNumber: String
  def userName: String
  def password: String
  def databaseName: String
}

@Singleton
class PlayBlockChainDatabaseConfiguration @Inject() (val config: Configuration)
    extends BlockChainDatabaseConfiguration {
  def serverName: String =
    config.get[String]("meso-alert.db.properties.serverName")
  def portNumber: String =
    config.get[String]("meso-alert.db.properties.portNumber")
  def userName: String = config.get[String]("meso-alert.db.properties.user")
  def password: String =
    config.get[String]("meso-alert.db.properties.password")
  def databaseName: String =
    config.get[String]("meso-alert.db.properties.databaseName")
}

@Singleton
class PostgresBlockChain @Inject() (
    db: BlockChainDatabaseConfiguration,
    params: NetParamsProvider
) extends BlockChainProvider {
  protected val blockStore = new PostgresFullPrunedBlockStore(
    params.get,
    1000,
    db.serverName + ":" + db.portNumber,
    db.databaseName,
    db.userName,
    db.password
  )
  lazy val get = new BlockChain(params.get, blockStore)
}

@Singleton
class MainNetParamsProvider extends NetParamsProvider {
  val get: MainNetParams = MainNetParams.get
}

@Singleton
class BlockChainPeerGroup @Inject() (
    params: NetParamsProvider,
    blockChain: BlockChainProvider
) extends PeerGroupProvider {
  lazy val get = new PeerGroup(params.get, blockChain.get)
}
