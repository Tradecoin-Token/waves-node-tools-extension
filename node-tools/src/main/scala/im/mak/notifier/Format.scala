package im.mak.notifier

import java.text.DecimalFormat

private[notifier] object Format {

  def waves(wavelets: Long): String = new DecimalFormat("###,###.########")
    .format((BigDecimal(wavelets) / 100000000).doubleValue())

}
