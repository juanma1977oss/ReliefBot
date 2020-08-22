package tarehart.rlbot.steps.strikes

import rlbot.render.NamedRenderer
import tarehart.rlbot.AgentOutput
import tarehart.rlbot.TacticalBundle
import tarehart.rlbot.input.CarData
import tarehart.rlbot.intercept.Intercept
import tarehart.rlbot.intercept.InterceptCalculator
import tarehart.rlbot.intercept.StrikePlanner
import tarehart.rlbot.intercept.strike.*
import tarehart.rlbot.math.BallSlice
import tarehart.rlbot.math.SpaceTime
import tarehart.rlbot.math.vector.Vector2
import tarehart.rlbot.math.vector.Vector3
import tarehart.rlbot.physics.ArenaModel
import tarehart.rlbot.physics.BallPhysics
import tarehart.rlbot.physics.ChipOption
import tarehart.rlbot.planning.GoalUtil
import tarehart.rlbot.planning.SteerUtil
import tarehart.rlbot.planning.cancellation.BallPathDisruptionMeter
import tarehart.rlbot.rendering.RenderUtil
import tarehart.rlbot.steps.NestedPlanStep
import tarehart.rlbot.time.Duration
import tarehart.rlbot.tuning.BotLog
import tarehart.rlbot.tuning.BotLog.println
import tarehart.rlbot.tuning.ManeuverMath
import java.awt.Color
import kotlin.math.min

/**
 * The philosophy here will be:
 * 1. Pick an intercept somewhat sloppily
 * 2. Steer toward that intercept until we're lined up well.
 * 3. Now we have a more refined ball slice / arrival speed.
 * 4. Simulate some hits on the ball which are based on driving straight and hitting the ball off-center
 * 5. Pick the hit with your favorite result. Now you have a sliceToCar vector to pass into the intercept calculator.
 * 6. Intercept is now at max refinement. Maybe stop doing hit simulation because we don't want to create an unstable
 *    Oscillation between changing ball slice vs changing sliceToCar
 * 7. If the ball slice is off the ground and requires a single jump:
 *    - Don't pre-plan it, just jump when you have exactly enough time to reach the required height
 *    - Once in the air, dodge if/when necessary to refine the contact
 *
 *
 * This is currently going poorly for jump stuff, largely because the ball radius multiplier in BallPhysics is screwing us up,
 * but also because the hits are really weak. If we dodge it'll be better. If we stretch higher we can accelerate
 * more before jumping.
 */
open class SlotKickStep(private val kickStrategy: KickStrategy) : NestedPlanStep() {

    private var slotStart: Vector3? = null
    private var slotEnd: Vector3? = null
    private var favoredChipOption: ChipOption? = null
    private var favoredSliceToCar: Vector3? = null
    private var isFinalMoments = false
    private var recentIntercept: Intercept? = null

    private val disruptionMeter = BallPathDisruptionMeter(5)

    override fun reset() {
        super.reset()
        slotStart = null
        slotEnd = null
        disruptionMeter.reset()
    }

    override fun canInterrupt(): Boolean {
        return super.canInterrupt() && !isFinalMoments
    }

    override fun doComputationInLieuOfPlan(bundle: TacticalBundle): AgentOutput? {

        val car = bundle.agentInput.myCarData
        val ballPath = bundle.tacticalSituation.ballPath

        if (disruptionMeter.isDisrupted(ballPath)) {
            BotLog.println("Ball path disrupted during slot kick.", car.playerIndex)
            cancelPlan = true
            return null
        }

        val overallPredicate = { cd: CarData, st: SpaceTime -> kickStrategy.looksViable(cd, st.space) }

        val distancePlot = bundle.tacticalSituation.expectedContact.distancePlot

        val sliceToCar = favoredSliceToCar ?: Vector3()
        val intercept = InterceptCalculator.getFilteredInterceptOpportunity(car, ballPath, distancePlot, sliceToCar, overallPredicate,
                strikeProfileFn = { ballSlice -> selectStrike(car, ballSlice, kickStrategy) }) ?:
        return null

        recentIntercept = intercept

        if (slotStart == null) {
            val arrivalHeight = intercept.ballSlice.space.z - ArenaModel.BALL_RADIUS + ManeuverMath.BASE_CAR_Z
            val chipOptions = BallPhysics.computeChipOptions(car.position, intercept.accelSlice.speed, intercept.ballSlice,
                    car.hitbox, (-25..25).map { it * .1F }, arrivalHeight)

            for ((index, chipOption) in chipOptions.withIndex()) {
                val color = RenderUtil.rainbowColor(index)
                car.renderer.drawLine3d(color, intercept.ballSlice.space, intercept.ballSlice.space + chipOption.velocity)
                chipOption.carSlice.render(car.renderer, color)
            }
        }

        slotEnd = intercept.space.withZ(car.position.z)

        val steerCorrection = SteerUtil.getCorrectionAngleRad(car, intercept.space)
        val firmStart = slotStart
        val firmEnd = intercept.space
        if (Math.abs(steerCorrection) < 0.03) {
            val easyKick = intercept.space - car.position
            val idealDirection = kickStrategy.getKickDirection(bundle, intercept.space, easyKick) ?:
                return null

            val arrivalHeight = intercept.ballSlice.space.z - ArenaModel.BALL_RADIUS + ManeuverMath.BASE_CAR_Z
            val chipOption = BallPhysics.computeBestChipOption(car.position, intercept.accelSlice.speed,
                    intercept.ballSlice, car.hitbox, idealDirection, arrivalHeight)

            if (chipOption == null) {
                println("Could not compute chip option", car.playerIndex)
                return null
            }

            favoredChipOption = chipOption
            favoredSliceToCar = chipOption.carSlice.space - intercept.ballSlice.space
            slotStart = car.position
            if (Duration.between(car.time, chipOption.carSlice.time).seconds < 0.7) {
                isFinalMoments = true
            }
        }

        if (firmStart != null) {
            val alignment = Vector2.alignment(firmStart.flatten(), car.position.flatten(), firmEnd.flatten())
            if (alignment < .9) {
                BotLog.println("Car fell out of the slot!", car.playerIndex)
                return null
            }

//            favoredChipOption?.let {
//                val renderer = NamedRenderer("slotKick ${car.playerIndex}")
//                renderer.startPacket()
//                renderer.drawLine3d(Color.GREEN, intercept.ballSlice.space, intercept.ballSlice.space + it.velocity)
//                it.carSlice.render(renderer, Color.GREEN)
//                RenderUtil.drawCircle(renderer, it.chipCircle, it.impactPoint.z, Color.WHITE)
//                renderer.finishAndSend()
//            }

            val jumpOutput = reflexManeuver(car, intercept, favoredChipOption, bundle)
            if (jumpOutput != null) {
                return jumpOutput
            }
        }

        drawSlot(car)

        if (car.boost < 1 && bundle.tacticalSituation.ballAdvantage.seconds > 0) {
            SteerUtil.getSensibleFlip(car, intercept.space)?.let {
                println("Front flip toward slot kick", bundle.agentInput.playerIndex)
                return startPlan(it, bundle)
            }
        }

        val toIntercept = intercept.space.flatten() - car.position.flatten()

        if (intercept.needsPatience) {
            return SteerUtil.getThereOnTime(car, intercept.toSpaceTime(), false)
        }

        return SteerUtil.steerTowardGroundPosition(
                car, intercept.space.flatten(),
                detourForBoost = firmStart == null && toIntercept.magnitude() > 70,
                conserveBoost = false)
    }

    private fun selectStrike(car: CarData, ballSpaceTime: BallSlice, kickStrategy: KickStrategy): StrikeProfile {

        val heightOfBall = ballSpaceTime.space.z

        if (heightOfBall < ChipStrike.MAX_HEIGHT_OF_BALL_FOR_CHIP) {
            return ChipStrike()
        }

        if (heightOfBall < ReflexJumpStrike.MAX_JUMP) {
            val enemyGoal = GoalUtil.getEnemyGoal(car.team).center
            if (enemyGoal.distance(ballSpaceTime.space) < 70) {
                // Need some finesse
                return DodgelessJumpStrike(heightOfBall)
            }
            // Boom it
            return ReflexJumpStrike(heightOfBall)
        }

        if (heightOfBall < DoubleJumpPokeStrike.MAX_BALL_HEIGHT_FOR_DOUBLE_JUMP_POKE) {
            return DoubleJumpPokeStrike(heightOfBall)
        }

        return AerialStrike(heightOfBall, kickStrategy)
    }

    private fun reflexManeuver(car: CarData, intercept: Intercept, chipOption: ChipOption?, bundle: TacticalBundle): AgentOutput? {
        intercept.strikeProfile.getPlan(car, intercept.toSpaceTime())?.let {
            return startPlan(it, bundle)
        }
        return null
    }

    fun drawSlot(car: CarData) {
        val start = slotStart
        val end = slotEnd ?: return
        if (start != null) {
            car.renderer.drawLine3d(Color.GREEN, start, end)
        } else {
            car.renderer.drawLine3d(Color.ORANGE,
                    car.position,
                    car.position.plus(car.orientation.noseVector.scaledToMagnitude(5)))
            car.renderer.drawLine3d(Color.ORANGE,
                    car.position,
                    car.position.plus((end - car.position).withZ(0).scaledToMagnitude(5)))
        }
    }

    override fun getLocalSituation(): String {
        val strikeType = recentIntercept?.strikeProfile?.style
        return "Slot kick - ${kickStrategy.javaClass.simpleName} - $strikeType"
    }
}
