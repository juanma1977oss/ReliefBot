package tarehart.rlbot.steps.demolition

import rlbot.cppinterop.RLBotDll
import rlbot.flat.QuickChatSelection
import tarehart.rlbot.AgentOutput
import tarehart.rlbot.TacticalBundle
import tarehart.rlbot.carpredict.AccelerationModel
import tarehart.rlbot.carpredict.CarInterceptPlanner
import tarehart.rlbot.carpredict.CarPredictor
import tarehart.rlbot.input.CarData
import tarehart.rlbot.math.vector.Vector3
import tarehart.rlbot.planning.Plan
import tarehart.rlbot.planning.SetPieces
import tarehart.rlbot.planning.SteerUtil
import tarehart.rlbot.rendering.RenderUtil
import tarehart.rlbot.steps.NestedPlanStep
import tarehart.rlbot.steps.blind.BlindStep
import tarehart.rlbot.tactics.SpikeRushTacticsAdvisor
import tarehart.rlbot.time.Duration
import tarehart.rlbot.time.GameTime
import tarehart.rlbot.tuning.BotLog
import java.awt.Color
import java.lang.Math.random

class DemolishEnemyStep(val isAdversityBot: Boolean = false, val specificTarget: CarData? = null,
                        val requireSupersonic: Boolean = true, private val isSpikeRush: Boolean = false) : NestedPlanStep() {

    enum class DemolishPhase {
        CHASE,
        AWAIT_LIFTOFF,
        JUMP,
        DODGE,
        LAND
    }

    // Toxic Quick Chats. Only active for `isAdversityBot`
    enum class ChatProgression {
        // Lined up and going for a demo
        Incoming,
        // Seconds away from demo
        Demoing,
        // Demo was a success
        Quip
    }

    class DemolishTransition(val output: AgentOutput, val phase: DemolishPhase)

    private var canDodge: Boolean = true
    //private var enemyIndex: Int? = null
    private var momentJumped: GameTime? = null
    private val SECONDS_BEFORE_CONTACT_TO_JUMP = 0.8
    private val SECONDS_TO_PREDICT = 4.0
    private val NEEDS_JUMP_HEIGHT = 0.5
    private var demolishPhase = DemolishPhase.CHASE
    private lateinit var carPredictor: CarPredictor
    private var enemyIndex: Int? = null
    private var deadline: GameTime? = null

    // 0 = chat every time
    // 0.5 = chat half the time
    // 1 = chat never
    private val shouldChat = isAdversityBot && random() > 0.3
    private var previousChatType = ChatProgression.Quip

    private fun resetChat() {
        previousChatType = ChatProgression.Quip
    }

    private fun progressChat(playerIndex: Int, desiredChat: ChatProgression) {
        if (!shouldChat) return

        // Only emit chat when we have just made a transition.
        if (previousChatType == desiredChat) return

        when(desiredChat) {
            ChatProgression.Incoming -> {
                val incomingChatOptions = arrayOf(QuickChatSelection.Information_InPosition, QuickChatSelection.Information_Incoming)
                RLBotDll.sendQuickChat(playerIndex, false, incomingChatOptions.random())
            }
            ChatProgression.Demoing -> {
                val demoingChatOptions = arrayOf(
                        QuickChatSelection.Custom_Useful_Demoing
                        // , QuickChatSelection.Information_NeedBoost
                        , QuickChatSelection.Apologies_MyFault
                        , QuickChatSelection.Custom_Toxic_404NoSkill
                        , QuickChatSelection.Custom_Toxic_DeAlloc
                )
                RLBotDll.sendQuickChat(playerIndex, false, demoingChatOptions.random())
            }
            ChatProgression.Quip -> {
                val quipChatOptions = arrayOf(
                    QuickChatSelection.Compliments_NiceBlock
                        , QuickChatSelection.Reactions_Siiiick
                        , QuickChatSelection.Reactions_Savage
                        , QuickChatSelection.Reactions_Calculated
                        , QuickChatSelection.Apologies_Oops
                        , QuickChatSelection.PostGame_ThatWasFun
                        , QuickChatSelection.Custom_Toxic_GitGut
                        , QuickChatSelection.Custom_Toxic_WasteCPU
                        , QuickChatSelection.PostGame_Gg
                        , QuickChatSelection.Compliments_WhatAPlay
                        , QuickChatSelection.PostGame_Rematch
                )
                RLBotDll.sendQuickChat(playerIndex, false, quipChatOptions.random())
            }
        }

        previousChatType = desiredChat
    }


    override fun doInitialComputation(bundle: TacticalBundle) {
        super.doInitialComputation(bundle)


    }

    override fun doComputationInLieuOfPlan(bundle: TacticalBundle): AgentOutput? {

        val car = bundle.agentInput.myCarData
        val oppositeTeam = bundle.agentInput.getTeamRoster(bundle.agentInput.team.opposite())

        val enemyCar = enemyIndex?.let { enemyInd -> oppositeTeam.firstOrNull { it.playerIndex == enemyInd } } ?:
        selectEnemyCar(bundle) ?: return null

        if (!::carPredictor.isInitialized || carPredictor.carIndex != enemyCar.playerIndex) {
            carPredictor = CarPredictor(enemyCar.playerIndex)
        }

        if(enemyCar.isDemolished) {
            progressChat(bundle.agentInput.playerIndex, ChatProgression.Quip)
            return null
        }

        if (isSpikeRush && demolishPhase == DemolishPhase.CHASE &&
                SpikeRushTacticsAdvisor.getBallCarrier(bundle.agentInput) != enemyCar) {
            BotLog.println("Ball carrier is not the guy I'm chasing, and this is spike rush! Giving up.", car.playerIndex)
            return null
        }

        if (car.hasWheelContact && !(demolishPhase == DemolishPhase.CHASE || demolishPhase == DemolishPhase.AWAIT_LIFTOFF)) {
            return null // We already attempted a midair dodge, and now we've hit the ground. Give up.
        }

        deadline?.let {
            if (car.time > it) {
                return null
            }
        }

        if (enemyCar.position.distance(car.position) < 30) {
            progressChat(bundle.agentInput.playerIndex, ChatProgression.Incoming)
            enemyIndex = enemyCar.playerIndex // Commit to demolishing this particular enemy
        }

        val transition = when (demolishPhase) {

            DemolishPhase.CHASE -> chase(bundle, enemyCar) ?: return null

            DemolishPhase.AWAIT_LIFTOFF -> DemolishTransition(
                    AgentOutput().withBoost().withJump(),
                    if (!car.hasWheelContact) DemolishPhase.JUMP else DemolishPhase.AWAIT_LIFTOFF)

            DemolishPhase.JUMP -> jump(bundle, enemyCar) ?: return null

            else -> DemolishTransition(AgentOutput().withThrottle(1.0), demolishPhase)
        }


        demolishPhase = transition.phase
        return transition.output
    }

    private fun chase(bundle: TacticalBundle, enemyCar: CarData): DemolishTransition? {
        val car = bundle.agentInput.myCarData

        if (!hasEnoughBoost(car) && requireSupersonic) {
            return null
        }

        val renderer = car.renderer

        val path = carPredictor.predictCarMotion(bundle, Duration.ofSeconds(SECONDS_TO_PREDICT))
        path.renderIn3d(renderer)
        val distancePlot = AccelerationModel.simulateAcceleration(car, Duration.ofSeconds(SECONDS_TO_PREDICT), car.boost)
        val carIntercept = CarInterceptPlanner.getCarIntercept(car, path, distancePlot)

        val deadlineSnapshot = deadline

        carIntercept?.let {

            if (deadlineSnapshot == null) {
                deadline = it.time.plusSeconds(2)
            }

            RenderUtil.drawSphere(renderer, it.space, 1.0, Color.RED)

            val secondsTillContact = Duration.between(car.time, it.time).seconds

            if (!car.isSupersonic && car.boost == 0F && enemyCar.velocity.dotProduct(car.velocity) > 0 &&
                    car.velocity.magnitude() > AccelerationModel.MEDIUM_SPEED * .8 &&
                    SteerUtil.isDrivingOnTarget(car, it.space.flatten())) {

                val output = startPlan(SetPieces.speedupFlip(), bundle) ?: return null
                return DemolishTransition(output, DemolishPhase.CHASE)
            }

            if (secondsTillContact <= SECONDS_BEFORE_CONTACT_TO_JUMP) {
                progressChat(bundle.agentInput.playerIndex, ChatProgression.Demoing)
                if (!enemyCar.hasWheelContact && it.space.z > NEEDS_JUMP_HEIGHT) {
                    momentJumped = car.time
                    return DemolishTransition(AgentOutput().withBoost().withJump(), DemolishPhase.AWAIT_LIFTOFF)
                }
            }

            return DemolishTransition(SteerUtil.steerTowardPositionAcrossSeam(car, it.space), DemolishPhase.CHASE)
        }

        return DemolishTransition(SteerUtil.steerTowardPositionAcrossSeam(car, enemyCar.position), DemolishPhase.CHASE)
    }

    private fun jump(bundle: TacticalBundle, enemyCar: CarData): DemolishTransition? {

        val car = bundle.agentInput.myCarData

        val simulationDuration = Duration.ofSeconds(SECONDS_BEFORE_CONTACT_TO_JUMP * 1.5)

        val path = carPredictor.predictCarMotion(bundle, simulationDuration)
        val renderer = car.renderer
        path.renderIn3d(renderer)
        val distancePlot = AccelerationModel.simulateAcceleration(car, simulationDuration, car.boost)
        val carIntercept = CarInterceptPlanner.getCarIntercept(car, path, distancePlot)

        if (carIntercept != null) {

            RenderUtil.drawSphere(renderer, carIntercept.space, 1.0, Color.RED)

            val selfPrediction = CarPredictor.doNaivePrediction(car, Duration.between(car.time, carIntercept.time), Vector3.ZERO)

            val toInterceptNow = carIntercept.space - car.position
            val toInterceptThen = carIntercept.space - selfPrediction.lastSlice.space

            val needsDoubleJump = carIntercept.space.z > 5 || toInterceptThen.z > 0.5


            if (needsDoubleJump && canDodge && !car.hasWheelContact) {
                BotLog.println("Needs double jump...", car.playerIndex)
                return workTowardDoubleJump(car, bundle)
            }

            val steerCorrection = car.velocity.flatten().correctionAngle(toInterceptNow.flatten())
            if (Math.abs(steerCorrection) > Math.PI / 20) {
                val immediateOutput = startPlan(Plan().unstoppable()
                        .withStep(BlindStep(Duration.ofMillis(50), AgentOutput().withBoost()))
                        .withStep(BlindStep(Duration.ofMillis(50), AgentOutput()
                                .withBoost()
                                .withRoll(-Math.signum(steerCorrection))
                                .withJump())), bundle) ?: return null
                return DemolishTransition(immediateOutput, DemolishPhase.DODGE)
            }
        }

        RenderUtil.drawSphere(renderer, enemyCar.position, 1.0, Color.GRAY)

        return DemolishTransition(AgentOutput().withJump().withBoost(), DemolishPhase.JUMP)
    }

    private fun workTowardDoubleJump(car: CarData, bundle: TacticalBundle): DemolishTransition? {
        val timeSinceJump = Duration.between(momentJumped!!, car.time)
        if (timeSinceJump < Duration.ofMillis(200)) {
            // Don't double jump yet, we need to get more benefit from our initial jump.
            return DemolishTransition(AgentOutput().withJump(), DemolishPhase.JUMP)
        }

        canDodge = false
        val immediateOutput = startPlan(Plan().unstoppable()
                .withStep(BlindStep(Duration.ofMillis(50), AgentOutput().withBoost()))
                .withStep(BlindStep(Duration.ofMillis(50), AgentOutput().withBoost().withJump()))
                .withStep(BlindStep(Duration.ofMillis(150), AgentOutput().withBoost().withJump().withPitch(-1.0))), bundle)
                ?: return null

        return DemolishTransition(immediateOutput, DemolishPhase.DODGE)
    }

    override fun getLocalSituation(): String {
        return "Demolishing enemy"
    }

    override fun canInterrupt(): Boolean {
        return super.canInterrupt() && demolishPhase == DemolishPhase.CHASE
    }

    companion object {
        fun hasEnoughBoost(car: CarData): Boolean {
            return car.isSupersonic || car.boost > 15
        }

        fun selectEnemyCar(bundle: TacticalBundle): CarData? {
            val oppositeTeam = bundle.agentInput.getTeamRoster(bundle.agentInput.team.opposite())
            val car = bundle.agentInput.myCarData
            val distancePlot = bundle.tacticalSituation.expectedContact.distancePlot
            return oppositeTeam.filter { !it.isDemolished }.minBy {
                // Go after the enemy we can reach soonest, with a bias for going after humans.
                AccelerationModel.getTravelTime(car, distancePlot, it.position)?.seconds ?: 6F - (if (car.isBot) 0 else 2 )
            }
        }
    }
}
