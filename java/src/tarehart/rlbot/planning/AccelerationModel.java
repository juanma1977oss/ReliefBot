package tarehart.rlbot.planning;

import mikera.vectorz.Vector2;
import mikera.vectorz.Vector3;
import tarehart.rlbot.AgentInput;
import tarehart.rlbot.math.DistanceTimeSpeed;
import tarehart.rlbot.math.TimeUtil;
import tarehart.rlbot.math.VectorUtil;
import tarehart.rlbot.physics.ArenaModel;
import tarehart.rlbot.physics.DistancePlot;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

public class AccelerationModel {

    public static final int SUPERSONIC_SPEED = 46;
    public static final int MEDIUM_SPEED = 28;
    public static final double FRONT_FLIP_SECONDS = 1.5;

    private static final Double TIME_STEP = 0.1;
    private static final double FRONT_FLIP_SPEED_BOOST = 10;
    private static final double SUB_MEDIUM_ACCELERATION = 15; // zero to medium in about 2 seconds.
    private static final double INCREMENTAL_BOOST_ACCELERATION = 8;
    private static final double BOOST_CONSUMED_PER_SECOND = 25;


    public static Optional<Double> getTravelSeconds(AgentInput input, DistancePlot plot, Vector3 target) {
        double distance = input.getMyPosition().distance(target);
        Optional<Double> travelTime = plot.getTravelTime(distance);
        double penaltySeconds = getSteerPenaltySeconds(input, target);
        return travelTime.map(time -> time + penaltySeconds);
    }

    public static double getSteerPenaltySeconds(AgentInput input, Vector3 target) {
        return Math.abs(SteerUtil.getCorrectionAngleRad(input, target)) * input.getMyVelocity().magnitude() * .02;
    }

    /*
    public static double simulateTravelTime(AgentInput input, Vector3 target, double boostBudget, boolean ignoreOrientation) {

        double distance = VectorUtil.flatten(input.getMyPosition()).distance(VectorUtil.flatten(target));
        double boostRemaining = boostBudget;

        double distanceSoFar = 0;
        double secondsSoFar = 0;
        double currentSpeed = input.getMyVelocity().magnitude();
        double steerPenaltySeconds = ignoreOrientation ? 0 : Math.abs(SteerUtil.getCorrectionAngleRad(input, target)) * currentSpeed * .02;

        while (distanceSoFar < distance) {
            double hypotheticalFrontFlipDistance = ((currentSpeed * 2 + FRONT_FLIP_SPEED_BOOST) / 2) * FRONT_FLIP_SECONDS;
            if (boostRemaining <= 0 && distanceSoFar + hypotheticalFrontFlipDistance < distance) {
                secondsSoFar += FRONT_FLIP_SECONDS;
                distanceSoFar += hypotheticalFrontFlipDistance;
                currentSpeed += FRONT_FLIP_SPEED_BOOST;
                continue;
            }

            double acceleration = getAcceleration(currentSpeed, boostRemaining > 0);
            currentSpeed += acceleration * TIME_STEP;
            distanceSoFar += currentSpeed * TIME_STEP;
            secondsSoFar += TIME_STEP;
            boostRemaining -= BOOST_CONSUMED_PER_SECOND * TIME_STEP;
        }

        double overshoot = distanceSoFar - distance;
        secondsSoFar -= overshoot / currentSpeed;

        return secondsSoFar + steerPenaltySeconds;
    }*/

    public static DistancePlot simulateAcceleration(AgentInput input, Duration duration, double boostBudget) {
        return simulateAcceleration(input, duration, boostBudget, Double.MAX_VALUE);
    }

    public static DistancePlot simulateAcceleration(AgentInput input, Duration duration, double boostBudget, double flipCutoffDistance) {

        double currentSpeed = input.getMyVelocity().magnitude();
        DistancePlot plot = new DistancePlot(new DistanceTimeSpeed(0, input.time, currentSpeed));

        double boostRemaining = boostBudget;

        double distanceSoFar = 0;
        double secondsSoFar = 0;

        double secondsToSimulate = TimeUtil.toSeconds(duration);

        while (secondsSoFar < secondsToSimulate) {
            double hypotheticalFrontFlipDistance = ((currentSpeed * 2 + FRONT_FLIP_SPEED_BOOST) / 2) * FRONT_FLIP_SECONDS;
            if (boostRemaining <= 0 && distanceSoFar + hypotheticalFrontFlipDistance < flipCutoffDistance) {
                secondsSoFar += FRONT_FLIP_SECONDS;
                distanceSoFar += hypotheticalFrontFlipDistance;
                currentSpeed += FRONT_FLIP_SPEED_BOOST;
                plot.addSlice(new DistanceTimeSpeed(distanceSoFar, input.time.plus(TimeUtil.toDuration(secondsSoFar)), currentSpeed));
                continue;
            }

            double acceleration = getAcceleration(currentSpeed, boostRemaining > 0);
            currentSpeed += acceleration * TIME_STEP;
            if (currentSpeed > SUPERSONIC_SPEED) {
                currentSpeed = SUPERSONIC_SPEED;
            }
            distanceSoFar += currentSpeed * TIME_STEP;
            secondsSoFar += TIME_STEP;
            boostRemaining -= BOOST_CONSUMED_PER_SECOND * TIME_STEP;
            plot.addSlice(new DistanceTimeSpeed(distanceSoFar, input.time.plus(TimeUtil.toDuration(secondsSoFar)), currentSpeed));

            if (currentSpeed >= SUPERSONIC_SPEED) {
                // It gets boring from now on. Put a slice at the very end.
                double secondsRemaining = secondsToSimulate - secondsSoFar;
                plot.addSlice(new DistanceTimeSpeed(distanceSoFar + SUPERSONIC_SPEED * secondsRemaining, input.time.plus(duration), SUPERSONIC_SPEED));
                break;
            }
        }

        return plot;
    }

    private static double getAcceleration(double currentSpeed, boolean hasBoost) {

        if (currentSpeed >= SUPERSONIC_SPEED || !hasBoost && currentSpeed >= MEDIUM_SPEED) {
            return 0;
        }

        double accel = 0;
        if (currentSpeed < MEDIUM_SPEED) {
            accel += SUB_MEDIUM_ACCELERATION;
        }
        if (hasBoost) {
            accel += INCREMENTAL_BOOST_ACCELERATION;
        }

        return accel;
    }

}
