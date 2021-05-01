package frc.robot.subsystems;

import com.ctre.phoenix.sensors.CANCoder;
import com.ctre.phoenix.sensors.CANCoderConfiguration;
import com.revrobotics.CANEncoder;
import com.revrobotics.CANPIDController;
import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMax.IdleMode;
import com.revrobotics.ControlType;

import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.wpilibj.geometry.Rotation2d;
import edu.wpi.first.wpilibj.kinematics.SwerveModuleState;
import frc.robot.Constants.DriveTrain;
import frc.robot.util.ModMath;


public class SwerveModuleMK3 {
  public final String NT_Name = "DT";  //expose data under DriveTrain table

  // Hardware PID settings in Constants.DriveTrain PIDFController 
  // PID slot for angle and drive pid on SmartMax controller
  final int kSlot = 0;    

  // Rev devices
  private final CANSparkMax driveMotor;
  private final CANSparkMax angleMotor;
  private final CANPIDController driveMotorPID;
  private final CANPIDController angleMotorPID; // sparkmax PID can only use internal NEO encoders
  private final CANEncoder  angleEncoder;       // aka internalAngle
  private final CANEncoder  driveEncoder;
  //CTRE devices
  private final CANCoder absEncoder;            // aka externalAngle (external to Neo/Smartmax)
  private double angleCmdInvert;

  /**
   * Warning CANCoder and CANEncoder are very close in name but very different.
   * 
   * CANCoder: CTRE, absolute position mode, +/- 180 CCW= positive CANEncoder:
   * RevRobotics, relative position only, must configure to CCW based on side &
   * gearing Continous positon so postion can be greater than 180 because it can
   * "infinitely" rotate. Cannot be inverted in Brushless mode, must invert motor
   * 
   */
  // keep for debugging
  CANCoderConfiguration absEncoderConfiguration;

  // NetworkTables
  String NTPrefix;

  // measurements made every period - public so they can be pulled for network tables...
  double m_internalAngle;    // measured Neo unbounded [deg]
  double m_externalAngle;    // measured CANCoder bounded +/-180 [deg]
  double m_velocity;         // measured velocity [ft/s]
  double m_angle_target;     // desired angle unbounded [deg]
  double m_vel_target;       // desired velocity [ft/s]

  /**
   * SwerveModuleMK3 - 
   * 
   *  SmartMax controllers used for angle and velocity motors. 
   * 
   *  SmartMax Velocity mode is used to close the velocity loop.  Units will match the units of the
   *  drive-wheel-diameter.  [ft/s]
   *  
   *  Angle in degrees is controlled using position mode on the SmartMax. The angle positon is not constrainted to
   *  +/- 180 degrees because the Neo has 32bit float resolution, so we can just let the postion grow or shrink
   *  based on the how many degrees we need to change.  We could rotate 1000's of time without going
   *  past the resolution of the SmartMax's position tracking.  [deg]
   *  
   *  Example: 
   *    cmd_angle = 175 ==> 175 + (n * 360) where  -Turns < n < Turns
   *              ==> ... -545 ==  -185 == 175 == 535 == 895 ...
   *  
   *  Minimum number of turns in one direction before we would have to consider overflow:
   *    Turns = posBitResolution / encoder-counts
   *    Turns = 2^23 / (42*12.8)  = 15,603            
   * 
   *  Batteries will need changing before then.
   * 
   */
String myprefix;

  public SwerveModuleMK3(CANSparkMax driveMtr, CANSparkMax angleMtr, double offsetDegrees, CANCoder absEnc,
      boolean invertAngleMtr, boolean invertAngleCmd, boolean invertDrive) {
    driveMotor = driveMtr;
    angleMotor = angleMtr;
    absEncoder = absEnc;

    // Always restore factory defaults - it removes gremlins
    driveMotor.restoreFactoryDefaults();
    angleMotor.restoreFactoryDefaults();

    // account for command sign differences if needed
    angleCmdInvert = (invertAngleCmd) ? -1.0 : 1.0;
    setMagOffset(offsetDegrees);

    // Drive Motor config
    driveMotor.setInverted(invertDrive);
    driveMotor.setIdleMode(IdleMode.kBrake);
    driveMotorPID = driveMotor.getPIDController();
    driveEncoder = driveMotor.getEncoder();
    // set driveEncoder to use ft/s
    driveEncoder.setPositionConversionFactor(Math.PI * DriveTrain.wheelDiameter / DriveTrain.kDriveGR); // mo-rot to ft
    driveEncoder.setVelocityConversionFactor(Math.PI * DriveTrain.wheelDiameter / DriveTrain.kDriveGR / 60.0); // mo-rpm to ft/s

    // Angle Motor config
    angleMotor.setInverted(invertAngleMtr);
    angleMotor.setIdleMode(IdleMode.kBrake);
    angleMotorPID = angleMotor.getPIDController();
    angleEncoder = angleMotor.getEncoder();

    // set angle endcoder to return values in deg and deg/s
    angleEncoder.setPositionConversionFactor(360.0 / DriveTrain.kSteeringGR); // mo-rotations to degrees
    angleEncoder.setVelocityConversionFactor(360.0 / DriveTrain.kSteeringGR / 60.0); // rpm to deg/s

    // SparkMax PID values
    DriveTrain.anglePIDF.copyTo(angleMotorPID, kSlot); // position mode
    DriveTrain.drivePIDF.copyTo(driveMotorPID, kSlot); // velocity mode

    // burn the motor flash
    angleMotor.burnFlash();
    driveMotor.burnFlash();
    
    //todo - do we still need the sleep with the re-order?
    sleep(50);   //hack to allow absEncoder config to be delivered???
    calibrate();
  }

  /**
   * This adjusts the absEncoder with the given offset to correct for CANCoder
   * mounting position. This value should be persistent accross power cycles.
   * 
   * Warning, we had to sleep afer setting configs before the absolute
   * position could be read in calibrate.
   * @param offsetDegrees
   */
  void setMagOffset(double offsetDegrees) {
    // adjust magnetic offset in absEncoder, measured constants.
    absEncoderConfiguration = new CANCoderConfiguration();
    absEncoder.getAllConfigs(absEncoderConfiguration); 
    // if different, update
    if (offsetDegrees != absEncoderConfiguration.magnetOffsetDegrees) {
      absEncoderConfiguration.magnetOffsetDegrees = offsetDegrees;
      absEncoder.configAllSettings(absEncoderConfiguration, 50);
    }
  }

   
  /**
   *  calibrate() - aligns Neo internal position with absolute encoder.
   *    This needs to be done at power up, or when the unbounded encoder gets
   *    close to its overflow point.
   */
  void calibrate() {
    // read absEncoder position, set internal angleEncoder to that value adjust for cmd inversion.
    double pos_deg = absEncoder.getAbsolutePosition();
    angleEncoder.setPosition(angleCmdInvert*pos_deg);   
  }


  // _set<>  for testing during bring up.
  public void _setInvertAngleCmd(boolean invert) {
    angleCmdInvert = (invert) ? -1.0 : 1.0;
    calibrate();
  }
  public void _setInvertAngleMotor(boolean invert) {
    angleMotor.setInverted(invert);
  }
  public void _setInvertDriveMotor(boolean invert) {
    driveMotor.setInverted(invert);
  }
  /**
   *  setNTPrefix - causes the network table entries to be created 
   *  and updated on the periodic() call.
   * 
   *  Use a short string to indicate which MK unit this is.
   * 
   */
  public SwerveModuleMK3 setNTPrefix(String prefix) {
    NTPrefix = "/MK3-" + prefix;
    myprefix = prefix;
    NTConfig();
    return this;
  }

  public String getNTPrefix() { 
    return NTPrefix; 
  }

  public void periodic() {
    //measure everything at same time
    m_internalAngle = angleEncoder.getPosition()*angleCmdInvert;
    m_externalAngle = absEncoder.getAbsolutePosition();
    m_velocity = driveEncoder.getVelocity();

    NTUpdate();
  }

  /**
   * This is the angle being controlled, so it should be thought of as the real
   * angle of the wheel.
   * 
   * @return SmartMax/Neo internal angle (degrees)
   */
  public Rotation2d getAngleRot2d() {
    return Rotation2d.fromDegrees(m_internalAngle); 
  }
  public double getAngle() {
    return m_internalAngle;
  }


  /**
   * External Angle is external to the SmartMax/Neo and is the absolute 
   * angle encoder. 
   * 
   * At power-up, this angle is used to calibrate the SmartMax PID controller.
   * 
   */
  public Rotation2d getAngleExternalRot2d() {
    return Rotation2d.fromDegrees(m_externalAngle);
  }
  public double getAngleExternal() {
    return m_externalAngle;
  }

  /**
   * 
   * @return velocity (ft/s)
   */
  public double getVelocity() {
    return m_velocity;
  }

  /**
   * Set the speed + rotation of the swerve module from a SwerveModuleState object
   * 
   * @param desiredState - A SwerveModuleState representing the desired new state
   *                     of the module
   */
  public void setDesiredState(SwerveModuleState desiredState) {
    SwerveModuleState state = desiredState;  //SwerveModuleState.optimize(desiredState, Rotation2d.fromDegrees(m_internalAngle));
   // use position control on angle with INTERNAL encoder, scaled internally for degrees
    m_angle_target = state.angle.getDegrees();

    // figure out how far we need to move, target - current, bounded +/-180
    double delta = ModMath.delta360(m_angle_target, m_internalAngle);
    // if we aren't moving, keep the wheels pointed where they are
    if (Math.abs(state.speedMetersPerSecond) < .01 ) delta = 0;

    // now add that delta to unbounded Neo angle, m_internal isn't range bound
    angleMotorPID.setReference(angleCmdInvert*(m_internalAngle + delta), ControlType.kPosition);
    
    // use velocity control, in ft/s (ignore variable name)
    driveMotorPID.setReference(state.speedMetersPerSecond, ControlType.kVelocity); 
  }

  public CANEncoder getDriveEncoder(){
    return driveEncoder;
  }
  /**
   * Network Tables data 
   * 
   * If a prefix is given for the module, NT entries will be created and updated on the periodic() call.
   * 
   */
  private NetworkTable table;
  private NetworkTableEntry nte_angle;
  private NetworkTableEntry nte_external_angle;
  private NetworkTableEntry nte_velocity;
  private NetworkTableEntry nte_angle_target;
  private NetworkTableEntry nte_vel_target;

  void NTConfig() {
    // direct networktables logging
    table = NetworkTableInstance.getDefault().getTable(NT_Name);
    nte_angle = table.getEntry(NTPrefix + "/angle");
    nte_external_angle = table.getEntry(NTPrefix +"/angle_ext");
    nte_velocity = table.getEntry(NTPrefix + "/velocity");
    nte_angle_target = table.getEntry(NTPrefix + "/angle_target");
    nte_vel_target = table.getEntry(NTPrefix + "/velocity_target");
    

  }

  void NTUpdate() {
    if (table == null) return;                   // not initialized, punt
    nte_angle.setDouble(m_internalAngle);
    nte_external_angle.setDouble(m_externalAngle);
    nte_velocity.setDouble(m_velocity);
    nte_angle_target.setDouble(m_angle_target);
    nte_vel_target.setDouble(m_vel_target);
  }

  void sleep( long ms) {
    try {
      Thread.sleep(ms);
    } catch (Exception e) { }
  }

}