package tarehart.rlbot.steps.strikes

import tarehart.rlbot.AgentInput
import tarehart.rlbot.AgentOutput
import tarehart.rlbot.carpredict.AccelerationModel
import tarehart.rlbot.input.BallTouch
import tarehart.rlbot.input.CarData
import tarehart.rlbot.intercept.*
import tarehart.rlbot.math.SpaceTime
import tarehart.rlbot.math.vector.Vector3
import tarehart.rlbot.physics.ArenaModel
import tarehart.rlbot.physics.BallPath
import tarehart.rlbot.physics.DistancePlot
import tarehart.rlbot.planning.SteerUtil
import tarehart.rlbot.steps.NestedPlanStep
import tarehart.rlbot.time.Duration
import tarehart.rlbot.time.GameTime
import tarehart.rlbot.tuning.BotLog.println
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Line2D
import java.util.*

class InterceptStep @JvmOverloads constructor(
        private val interceptModifier: Vector3,
        private val interceptPredicate: (CarData, SpaceTime) -> Boolean = { _, _ -> true }
) : NestedPlanStep() {
    override fun getLocalSituation(): String {
        return  "Working on intercept"
    }

    private var originalIntercept: Intercept? = null
    private var chosenIntercept: Intercept? = null
    private var originalTouch: BallTouch? = null

    override fun doComputationInLieuOfPlan(input: AgentInput): AgentOutput? {

        val carData = input.myCarData

        val ballPath = ArenaModel.predictBallPath(input)
        val fullAcceleration = AccelerationModel.simulateAcceleration(carData, Duration.ofSeconds(7.0), carData.boost, 0.0)

        val soonestIntercept = getSoonestIntercept(carData, ballPath, fullAcceleration, interceptModifier, interceptPredicate)
        if (soonestIntercept == null) {
            println("No intercept option found, aborting.", input.playerIndex)
            return null
        }

        chosenIntercept = soonestIntercept

        val launchPlan = StrikePlanner.planImmediateLaunch(input.myCarData, soonestIntercept)

        launchPlan?.let {
            it.unstoppable()
            return startPlan(it, input)
        }

        if (originalIntercept == null) {
            originalIntercept = soonestIntercept
            originalTouch = input.latestBallTouch.orElse(null)

        } else {

            if (originalIntercept?.let { ballPath.getMotionAt(it.time)?.space?.distance(it.space)?.takeIf { it > 10 } } != null) {
                println("Ball slices has diverged from expectation, will quit.", input.playerIndex)
                zombie = true
            }

            if (originalTouch?.position ?: Vector3() != input.latestBallTouch.map({it.position}).orElse(Vector3())) {
                // There has been a new ball touch.
                println("Ball has been touched, quitting intercept", input.playerIndex)
                return null
            }
        }


        return getThereOnTime(input, soonestIntercept)
    }

    private fun getThereOnTime(input: AgentInput, intercept: Intercept): AgentOutput {

        val car = input.myCarData

        SteerUtil.getSensibleFlip(car, intercept.space)?.let {
            println("Front flip toward intercept", input.playerIndex)
            startPlan(it, input)
        }?.let { return it }

        val timeToIntercept = Duration.between(car.time, intercept.time)
        val motionAfterStrike = intercept.distancePlot
                .getMotionAfterDuration(car, intercept.space, timeToIntercept, intercept.strikeProfile)

        if (motionAfterStrike != null) {
            val maxDistance = motionAfterStrike.distance
            val distanceToIntercept = car.position.flatten().distance(intercept.space.flatten())
            val pace = maxDistance / distanceToIntercept
            val averageSpeedNeeded = distanceToIntercept / timeToIntercept.seconds
            val currentSpeed = car.velocity.magnitude()

            val agentOutput = SteerUtil.steerTowardGroundPosition(car, intercept.space.flatten(), car.boost <= intercept.airBoost)
            if (pace > 1.1 && currentSpeed > averageSpeedNeeded) {
                // Slow down
                agentOutput.withAcceleration(0.0).withBoost(false).withDeceleration(Math.max(0.0, pace - 1.5)) // Hit the brakes, but keep steering!
                if (car.orientation.noseVector.dotProduct(car.velocity) < 0) {
                    // car is going backwards
                    agentOutput.withDeceleration(0.0).withSteer(0.0)
                }
            }
            return agentOutput
        } else {
            val output = SteerUtil.getThereOnTime(car, intercept.toSpaceTime())
            if (car.boost <= intercept.airBoost + 5) {
                output.withBoost(false)
            }
            return output
        }
    }

    override fun drawDebugInfo(graphics: Graphics2D) {

        super.drawDebugInfo(graphics)

        chosenIntercept?.let {
            graphics.color = Color(214, 136, 29)
            graphics.stroke = BasicStroke(1f)
            val (x, y) = it.space.flatten()
            val crossSize = 2
            graphics.draw(Line2D.Double(x - crossSize, y - crossSize, x + crossSize, y + crossSize))
            graphics.draw(Line2D.Double(x - crossSize, y + crossSize, x + crossSize, y - crossSize))
        }
    }

    companion object {
        val AERIAL_STRIKE_PROFILE = StrikeProfile(0.0, 0.0, 0.0, 0.0, StrikeProfile.Style.AERIAL)
        val FLIP_HIT_STRIKE_PROFILE = StrikeProfile(0.0, 0.0, 10.0, .4, StrikeProfile.Style.FLIP_HIT)

        fun getSoonestIntercept(
                carData: CarData,
                ballPath: BallPath,
                fullAcceleration: DistancePlot,
                interceptModifier: Vector3,
                interceptPredicate: (CarData, SpaceTime) -> Boolean): Intercept? {

            val interceptOptions = ArrayList<Intercept>()

            getAerialIntercept(carData, ballPath, interceptModifier, interceptPredicate)?.let { interceptOptions.add(it) }
            getJumpHitIntercept(carData, ballPath, fullAcceleration, interceptModifier, interceptPredicate)?.let { interceptOptions.add(it) }
            getFlipHitIntercept(carData, ballPath, fullAcceleration, interceptModifier, interceptPredicate)?.let { interceptOptions.add(it) }

            return interceptOptions.stream().sorted(Comparator.comparing<Intercept, GameTime>({ intercept -> intercept.time })).findFirst().orElse(null)
        }

        private fun getAerialIntercept(carData: CarData, ballPath: BallPath, interceptModifier: Vector3, interceptPredicate: (CarData, SpaceTime) -> Boolean): Intercept? {
            if (carData.boost < AirTouchPlanner.BOOST_NEEDED_FOR_AERIAL) return null

            val distance = carData.position.flatten().distance(ballPath.startPoint.space.flatten())
            val futureBall = ballPath.getMotionAt(carData.time.plusSeconds(distance * .02)) ?: return null
            val averageNoseVector = futureBall.space.minus(carData.position).normaliseCopy()

            val budgetAcceleration = AccelerationModel.simulateAirAcceleration(carData, Duration.ofSeconds(4.0), averageNoseVector.flatten().magnitude())

            return InterceptCalculator.getFilteredInterceptOpportunity(carData, ballPath, budgetAcceleration, interceptModifier,
                    { cd, st -> interceptPredicate.invoke(cd, st) && AirTouchPlanner.isVerticallyAccessible(cd, st) },
                    { AERIAL_STRIKE_PROFILE })
        }

        private fun getJumpHitIntercept(carData: CarData, ballPath: BallPath, fullAcceleration: DistancePlot, interceptModifier: Vector3, interceptPredicate: (CarData, SpaceTime) -> Boolean): Intercept? {
            return InterceptCalculator.getFilteredInterceptOpportunity(
                    carData, ballPath, fullAcceleration, interceptModifier,
                    { cd, st -> interceptPredicate.invoke(cd, st) && AirTouchPlanner.isJumpHitAccessible(cd, st) },
                    { space: Vector3 -> AirTouchPlanner.getJumpHitStrikeProfile(space) })
        }

        private fun getFlipHitIntercept(carData: CarData, ballPath: BallPath, fullAcceleration: DistancePlot, interceptModifier: Vector3, interceptPredicate: (CarData, SpaceTime) -> Boolean): Intercept? {
            return InterceptCalculator.getFilteredInterceptOpportunity(
                    carData, ballPath, fullAcceleration, interceptModifier,
                    { cd, st -> interceptPredicate.invoke(cd, st) && AirTouchPlanner.isFlipHitAccessible(st) },
                    { FLIP_HIT_STRIKE_PROFILE })
        }
    }
}
