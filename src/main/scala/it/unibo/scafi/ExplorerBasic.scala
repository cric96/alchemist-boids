package it.unibo.scafi

import it.unibo.scafi.space.Point3D

class ExplorerBasic extends Agent {

  override def main(): Any = {

    val source = senseEnvData[Double]("info") > 0
    val direction = G[Point3D](source, Point3D.Zero, p => p + nbrVector(), nbrRange)

    node.put("hue", mid()) // for visualization
    node.put("directionArray", Array(direction.x, direction.y))

    // compute angle from direction
    node.put("center", math.atan2(-1 * direction.y, direction.x))
    val state = computeState()
    node.put("state", state)
    // node.put("action", policy(state))
  }

}
