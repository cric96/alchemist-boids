package it.unibo.alchemist.model.implementations.reactions

import it.unibo.alchemist.loader.deployments.Grid
import it.unibo.alchemist.model.implementations.actions.RunScafiProgram
import it.unibo.alchemist.model.implementations.molecules.SimpleMolecule
import it.unibo.alchemist.model.implementations.nodes.SimpleNodeManager
import it.unibo.alchemist.model.interfaces._
import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist
import it.unibo.learning.Box
import it.unibo.learning.abstractions.{
  ActionSpace,
  AgentState,
  ArrayReplayBuffer,
  Contextual,
  QueueReplayBuffer,
  ReplayBuffer
}
import it.unibo.learning.agents.Learner
import it.unibo.learning.network.torch.writer
import org.apache.commons.math3.random.RandomGenerator

import scala.jdk.CollectionConverters.IterableHasAsScala

class GlobalLearnerDecentralisedAgentCollective[T, P <: Position[P]](
    environment: Environment[T, P],
    timeDistribution: TimeDistribution[T],
    random: RandomGenerator,
    layerMolecule: Molecule,
    val learner: Learner,
    bufferSize: Int,
    windowSize: Int,
    batchSize: Int,
    actionSpace: ActionSpace.Space,
    episodeLength: Int,
    box: Box,
    learningUpdate: Int
) extends AbstractGlobalReaction[T, P](environment, timeDistribution)
    with AbstractGlobalLearner {
  private val randomScala = new ScafiIncarnationForAlchemist.AlchemistRandomWrapper(random)
  private val buffer = new ArrayReplayBuffer(bufferSize, randomScala)
  private var actionMemory: Seq[Int] = Seq.empty
  private var stateMemory: Seq[AgentState] = Seq.empty
  private var totalRewardPerEpisode: Double = 0
  // private val extractor = new DensityExtractor()
  learner.injectCentralAgent(this)

  override protected def executeBeforeUpdateDistribution(): Unit = if (environment.getSimulation.getTime.toDouble > 1) {
    val currentTime = environment.getSimulation.getTime.toDouble
    val currentStates = states

    val currentActions = learner.policyBatch(states)
    improvePolicy(currentStates)
    actionMemory = currentActions
    stateMemory = currentStates
    val toPerform = currentStates.zip(currentActions).map { case (state, action) => (state.me -> action) }.toMap
    performAction(toPerform)
    if ((currentTime.toInt % episodeLength) == 0) {
      decayable.foreach(_._2.update())
      decayable.foreach { case (name, reference) =>
        writer.add_scalar(name, reference.value.toString.toDouble, environment.getSimulation.getTime.toDouble.toInt)
      }
      writer.add_scalar("total reward per episode", totalRewardPerEpisode, environment.getSimulation.getTime.toDouble.toInt)
      totalRewardPerEpisode = 0
      val newPosition = new Grid(
        environment,
        random,
        0.0f,
        0.0f,
        box.width,
        box.width,
        box.step,
        box.step,
        box.randomness,
        box.randomness
      ).asScala
      newPosition.zip(agents).foreach { case (position, node) =>
        resetNode(position.asInstanceOf[P], node)
      }
      initializationComplete(environment.getSimulation.getTime, environment)
    }
  }
  def states: Seq[AgentState] = managers.map(manager => manager.get[AgentState]("state"))

  def improvePolicy(states: Seq[AgentState]): Unit = {
    if (stateMemory.nonEmpty) {
      var totalReward = 0.0
      var partialRewardMap = Map.empty[String, Double]
      stateMemory.zip(actionMemory).zip(states).foreach { case ((previousState, action), newState) =>
        val (reward, partial) = rewardFunction(previousState, newState, action, 0.0)
        // combine partial reward
        partialRewardMap = partialRewardMap ++ partial.map { case (key, value) =>
          key -> (partialRewardMap.getOrElse(key, 0.0) + value)
        }
        totalReward += reward
        buffer.put(previousState, action, reward, newState)
      }
      partialRewardMap.foreach { case (key, value) =>
        writer.add_scalar(key, value / states.size, environment.getSimulation.getTime.toDouble.toInt)
      }
      writer.add_scalar("Reward", totalReward / states.size, environment.getSimulation.getTime.toDouble.toInt)
      val sample = buffer.sample(batchSize)
      if (sample.nonEmpty) learner.update(sample)
      totalRewardPerEpisode += totalReward
    }
  }

  def rewardFunction(
      previousState: AgentState,
      currentState: AgentState,
      action: Int,
      collectiveReward: Double
  ): (Double, Map[String, Double]) = {
    val target = 30
    val mySelf = currentState.neighborhoodSensing.head(currentState.me)
    val center = environment.makePosition(500, 500) // just for now
    val myPosition = environment.getPosition(environment.getNodeByID(currentState.me))
    val distanceReward = 1 - ((center.distanceTo(myPosition)) / 500)
    val connectionReward = if (currentState.neighborhoodSensing.head.size < 2) 0 else 1
    val maxDistance = currentState.neighborhoodSensing.head.minBy(_._2.distance[Double])._2.distance[Double]
    val minDistance = currentState.neighborhoodSensing.head.maxBy(_._2.distance[Double])._2.distance[Double]
    val deltaMax = maxDistance - target
    val deltaMin = -(minDistance - target)
    val collision = 1 - (deltaMax + deltaMin) / 300
    (distanceReward + connectionReward, Map(
      "distance reward" -> distanceReward,
      "connection reward" -> connectionReward,
      "collision" -> collision
    ))
    //distanceReward + connectionReward + collision
    /*if (mySelf.data[Double] > 0) { 0 }
    else if (currentState.neighborhoodSensing.size < 2) { -10 }
    else { -1 }*/

  }

  def resetNode(position: P, node: Node[T]): Unit = {
    node.getReactions.asScala.toList
      .collect { case e: Event[T] => e }
      .flatMap(_.getActions.asScala.toList)
      .collect { case execution: RunScafiProgram[T, P] => execution }
      .foreach(_.resetNeighborhood())
    val manager = new SimpleNodeManager[T](node)
    manager.remove("state")
    environment.moveNodeToPosition(node, position)
  }

  def performAction(actionsIndex: Map[Int, Int]): Unit = {
    val actions = actionsIndex.map { case (id, index) => id -> actionSpace(index) }
    actions
      .map { case (id, (angle, module)) =>
        (environment.getNodeByID(id), (angle, module))
      }
      .foreach { case (node, (angle, module)) =>
        node.setConcentration(new SimpleMolecule("angle"), angle.asInstanceOf[T])
        node.setConcentration(new SimpleMolecule("intensity"), module.asInstanceOf[T])
      }
    val deltaVector = actions.map { case (id, (angle, module)) =>
      id -> (module * math.cos(angle), module * math.sin(angle))
    }
    deltaVector
      .map { case (id, movement) => environment.getNodeByID(id) -> movement }
      .foreach { case (node, (dx, dy)) =>
        environment.moveNodeToPosition(node, environment.getPosition(node).plus(Array(dx * 10, -dy * 10)))
      }
  }
}
