package im.mak.nodetools

import java.text.DecimalFormat

private[nodetools] object Format {

  def waves(wavelets: Long): String = new DecimalFormat("###,###.########")
    .format((BigDecimal(wavelets) / 100000000).doubleValue())

}
