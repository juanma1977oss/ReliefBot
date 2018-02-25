package tarehart.rlbot.bots

import tarehart.rlbot.AgentInput
import tarehart.rlbot.AgentOutput
import tarehart.rlbot.planning.Plan
import tarehart.rlbot.planning.SetPieces

class JumpingBeanBot(team: Team, playerIndex: Int) : Bot(team, playerIndex) {

    override fun getOutput(input: AgentInput): AgentOutput {

        if (Plan.activePlanKt(currentPlan) == null) {
            currentPlan = SetPieces.jumpSuperHigh(10.0)
        }

        return currentPlan?.getOutput(input) ?: AgentOutput()
    }
}
