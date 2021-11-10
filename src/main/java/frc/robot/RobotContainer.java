// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot;

import java.util.List;

import edu.wpi.first.wpilibj.controller.PIDController;
import edu.wpi.first.wpilibj.controller.ProfiledPIDController;
import edu.wpi.first.wpilibj.geometry.Pose2d;
import edu.wpi.first.wpilibj.geometry.Rotation2d;
import edu.wpi.first.wpilibj.geometry.Translation2d;
import edu.wpi.first.wpilibj.trajectory.Trajectory;
import edu.wpi.first.wpilibj.trajectory.TrajectoryConfig;
import edu.wpi.first.wpilibj.trajectory.TrajectoryGenerator;
import edu.wpi.first.wpilibj.trajectory.TrapezoidProfile;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.InstantCommand;
import edu.wpi.first.wpilibj2.command.SwerveControllerCommand;
import frc.robot.Constants.DriverPrefs;
import frc.robot.commands.SwerveDriveCommand;
import frc.robot.subsystems.Sensors_Subsystem;
import frc.robot.subsystems.SwerveDrivetrain;
import frc.robot.subsystems.hid.HID_Xbox_Subsystem;
import frc.robot.subsystems.hid.XboxButton;
import frc.robot.subsystems.ifx.DriverControls.Id;

/**
 * This class is where the bulk of the robot should be declared. Since Command-based is a
 * "declarative" paradigm, very little robot logic should actually be handled in the {@link Robot}
 * periodic methods (other than the scheduler calls). Instead, the structure of the robot (including
 * subsystems, commands, and button mappings) should be declared here.
 */
public class RobotContainer {
  static RobotContainer  rc;
  public static RobotContainer   RC() {return rc;}

  public final HID_Xbox_Subsystem driverControls;
  public final Sensors_Subsystem sensors;
  private final SwerveDrivetrain drivetrain;

  /** The container for the robot. Contains subsystems, OI devices, and commands. */
  public RobotContainer() {
    RobotContainer.rc = this;

    //create our subsystems
    sensors = new Sensors_Subsystem();
    driverControls = new HID_Xbox_Subsystem(DriverPrefs.VelExpo, DriverPrefs.RotationExpo, DriverPrefs.StickDeadzone); 
    drivetrain = new SwerveDrivetrain();

    // set default commands
    drivetrain.setDefaultCommand(new SwerveDriveCommand(drivetrain, driverControls));

    setDriverButtons();
    setAssistantButtons();
    
  }

  /**
  * Driver xbox controller button bindings
  * <ul>
  * <li> B - Toggle field relative </li>
  * <li> A - Trajectory Follow Test </li>
  * </ul>
  */
  void setDriverButtons(){

    //B - Toggle field relative
    driverControls.bind(Id.Driver, XboxButton.B).whenPressed(new InstantCommand( drivetrain::toggleFieldRealitiveMode ));
  
    //A - Trajectory Test
    driverControls.bind(Id.Driver, XboxButton.A).whenPressed(getTrajectoryFollowTestCommand());
  

  }

    /**
  * Assistant xbox controller button bindings
  * <ul>
  * <li> TBD </li>
  * </ul>
  */
  void setAssistantButtons(){

  }

  // testing 
  void test_controls() {
    // var cmd = new MK3_AngleSpeed(driverControls, drivetrain, 0);  // FL, FR, BL, BR (0..3)
    //drivetrain.setDefaultCommand(cmd);

    //testing commands, speed in feet per sec, angle in degrees
    //driverControls.bind(Id.Driver, XboxPOV.POV_UP).whenHeld(new SwerveDriveTest(drivetrain, 1, 0));
    //driverControls.bind(Id.Driver, XboxPOV.POV_RIGHT).whenHeld(new SwerveDriveTest(drivetrain, 1, 90));
    //driverControls.bind(Id.Driver, XboxPOV.POV_DOWN).whenHeld(new SwerveDriveTest(drivetrain, 1, 180));
    //driverControls.bind(Id.Driver, XboxPOV.POV_LEFT).whenHeld(new SwerveDriveTest(drivetrain, 1, -90));
  }

  public Command getTrajectoryFollowTestCommand (){

    // An example trajectory to follow.  All units in meters.
    Trajectory exampleTrajectory =
      TrajectoryGenerator.generateTrajectory(
        new Pose2d(0.0, 0.0, new Rotation2d(-Math.PI / 2.0)),
        List.of(
          new Translation2d(0.0, -0.5),
          new Translation2d(1.0, 0.5)
        ),
        new Pose2d(1.0, 0.0, new Rotation2d(Math.PI / 2.0)),
        new TrajectoryConfig(1, 1) //max velocity, max accel
      );
      
      SwerveControllerCommand swerveControllerCommand =
      new SwerveControllerCommand(
          exampleTrajectory,
          drivetrain::getPose, // Functional interface to feed supplier
          drivetrain.getKinematics(),
          // Position controllers 
          new PIDController(1.0, 0.0, 0.0),
          new PIDController(1.0, 0.0, 0.0),
          new ProfiledPIDController(1, 0, 0, new TrapezoidProfile.Constraints(6.28, 3.14)),
            // Here, our rotation profile constraints were a max velocity
            // of 1 rotation per second and a max acceleration of 180 degrees
            // per second squared
          drivetrain::setModuleStates,
          drivetrain);

        // Reset odometry to the starting pose of the trajectory.
    drivetrain.setPose(exampleTrajectory.getInitialPose());

    // Run path following command, then stop at the end.
    return swerveControllerCommand.andThen(() -> drivetrain.drive(0, 0, 0));

  }
}
