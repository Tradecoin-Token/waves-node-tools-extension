package im.mak.nodetools

trait NotificationService {
  def info(message: String): Unit
  def warn(message: String): Unit
  def error(message: String): Unit
}
