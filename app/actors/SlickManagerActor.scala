//package actors
//
//import actors.AuthenticationActor.Die
//import akka.actor.{Actor, ActorRef, PoisonPill, Props}
//import com.google.inject.Inject
//import com.google.inject.assistedinject.Assisted
//import services.MemPoolWatcherService
//import dao._
//
//object SlickManagerActor {
//
//  trait Factory {
//    def apply(out: ActorRef): Actor
//  }
//    case class Die()
//
//    def props(memPoolWatcher: MemPoolWatcherService, slickTransactionUpdateDao: SlickTransactionUpdateDao): Props =
//        Props(new SlickManagerActor(memPoolWatcher, slickTransactionUpdateDao))
//
//
//}
//
//class SlickManagerActor @Inject()(@Assisted val memPoolWatcher: MemPoolWatcherService,
//                                  val slickTransactionUpdateDao: SlickTransactionUpdateDao)
//  extends Actor with TxUpdateActor {
//
//
//  override def preStart(): Unit = {
//    super.preStart()
//    registerWithWatcher()
//    slickTransactionUpdateDao.init()
//
//  }
//
//  override def receive: Receive = {
//    case tx: TxUpdate => slickTransactionUpdateDao.record(tx)
//    case Die => self ! PoisonPill
//  }
//
//}