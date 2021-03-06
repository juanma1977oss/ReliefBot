package tarehart.rlbot.planning

import tarehart.rlbot.math.BallSlice
import tarehart.rlbot.math.Clamper
import tarehart.rlbot.math.Plane
import tarehart.rlbot.math.Rectangle
import tarehart.rlbot.math.vector.Vector3
import tarehart.rlbot.physics.ArenaModel
import tarehart.rlbot.physics.BallPath
import kotlin.math.max
import kotlin.math.sign


class SoccerGoal(negativeSide: Boolean): Goal(negativeSide) {
    override val center: Vector3
    override val scorePlane: Plane

    private val box: Rectangle

    init {

        center = Vector3(0.0, GOAL_DISTANCE * if (negativeSide) -1 else 1, 0.0)

        scorePlane = Plane(
                Vector3(0.0, (if (negativeSide) 1 else -1).toDouble(), 0.0),
                Vector3(0.0, (GOAL_DISTANCE + 2) * if (negativeSide) -1 else 1, 0.0))

        box = if (negativeSide)
            Rectangle(ZoneDefinitions.BLUEBOX.awtArea.bounds2D)
        else
            Rectangle(ZoneDefinitions.ORANGEBOX.awtArea.bounds2D)
    }


    override fun getNearestEntrance(ballPosition: Vector3, padding: Number): Vector3 {

        val adjustedExtent = EXTENT - ArenaModel.BALL_RADIUS - padding.toFloat()
        val adjustedHeight = max(GOAL_HEIGHT - ArenaModel.BALL_RADIUS - padding.toFloat(), ArenaModel.BALL_RADIUS)
        val x = Clamper.clamp(ballPosition.x, -adjustedExtent, adjustedExtent)
        val z = Clamper.clamp(ballPosition.z, ArenaModel.BALL_RADIUS, adjustedHeight)
        return Vector3(x, center.y, z)
    }

    /**
     * From shooter's perspective
     */
    override fun getLeftPost(padding: Number): Vector3 {
        return Vector3(center.x - (EXTENT - padding.toFloat()) * sign(center.y), center.y, center.z)
    }

    /**
     * From shooter's perspective
     */
    override fun getRightPost(padding: Number): Vector3 {
        return Vector3(center.x + (EXTENT - padding.toFloat()) * sign(center.y), center.y, center.z)
    }

    fun isInBox(position: Vector3): Boolean {
        return box.contains(position.flatten())
    }

    override fun predictGoalEvent(ballPath: BallPath): BallSlice? {
        return ballPath.getPlaneBreak(ballPath.startPoint.time, scorePlane, true, increment = 4)
    }

    override fun isGoalEvent(planeBreakLocation: Vector3): Boolean {
        return true
    }

    companion object {

        private const val GOAL_DISTANCE = 102.0F
        const val GOAL_HEIGHT = 12F
        const val EXTENT = 17.8555F
    }
}
