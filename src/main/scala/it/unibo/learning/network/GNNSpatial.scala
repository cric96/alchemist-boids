package it.unibo.learning.network

import it.unibo.learning.abstractions.{AgentState, Contextual}
import it.unibo.learning.network.NeuralNetworkRL.Spatial
import it.unibo.learning.network.torch._
import me.shadaj.scalapy.py
import me.shadaj.scalapy.py.Any.from
import me.shadaj.scalapy.py.{PyQuote, SeqConverters}

class GNNSpatial(
    hiddenSize: Int,
    val actionSpace: List[Any],
    val encoder: NeuralNetworkEncoder = new UnboundedSpatialEncoder()
) extends NeuralNetworkRL {
  val True = torch.tensor(Seq(true).toPythonCopy)

  override val underlying: py.Dynamic = GNNDQN(encoder.shape.head, hiddenSize, actionSpace.size)

  override def forward(input: py.Dynamic)(implicit session: PythonMemoryManager.Session): py.Dynamic = {
    import session._
    val converted = input.as[Seq[py.Dynamic]].map(_.record())
    // println(converted(1))
    underlying((converted.head).record(), converted(1)).record().bracketAccess(converted(2).record())
  }

  override def policy(device: py.Any): (AgentState) => Int =
    // NeuralNetworkRL.policyFromNetwork(this, Seq(1, neigh * dataSpaceMultiplier), device)
    state => {
      val session = PythonMemoryManager.session()
      // context
      import session._
      val unsafeState = encoder.encode(state).as[py.Dynamic]
      val x = torch.tensor(unsafeState.bracketAccess(0).record()).record()
      val edge_index = torch.tensor(unsafeState.bracketAccess(1).record()).record()

      py.`with`(torch.no_grad()) { _ =>
        val data = underlying(encoder.normalize(x.to(device)).record(), edge_index.to(device).record()).record()
        val elements = data.tolist().record().bracketAccess(0).record()
        val max = py.Dynamic.global.max(elements)
        val index = elements.index(max).as[Int]
        session.clear()
        index
      }
    }
  override def cloneNetwork: NeuralNetworkRL = new GNNSpatial(hiddenSize, actionSpace, encoder)

  override def policyBatch(device: py.Any): Seq[AgentState] => Seq[Int] = { states =>
    implicit val session = PythonMemoryManager.session()
    // context
    import session._
    val batch = encoder.encodeBatch(
      states.map(encoder.encode),
      device
    )
    py.`with`(torch.no_grad()) { _ =>
      val result = this.forward(batch).record()
      val max = result
        .max(1)
        .record()
        .bracketAccess(1)
        .record()
      val index = max.tolist().as[Seq[Int]]
      session.clear()
      index
    }
  }

}
