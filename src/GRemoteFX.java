import controlP5.Button;
import controlP5.ControlEvent;
import controlP5.ControlFont;
import controlP5.ControlGroup;
import controlP5.ControlP5;
import controlP5.DropdownList;
import controlP5.Numberbox;
import controlP5.Textarea;
import controlP5.Textfield;
import controlP5.Textlabel;
import controlP5.Toggle;
import processing.core.PApplet;
import processing.serial.Serial;

import java.io.File;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.usb4java.*;

public class GRemoteFX extends Application {
	private int baudrate = 115200;

	private Integer[] baud_values = {9600, 19200, 38400, 57600, 115200};

	@Override
	public void start(Stage primary) throws Exception {
		primary.setTitle("GRemoteFX");

		HBox hbox = new HBox();

		MenuButton baudDropDown = new MenuButton("BAUD");
		for (int i = 0; i < baud_values.length; i++) {
			baudDropDown.getItems()
					.add(new MenuItem(baud_values[i].toString()));
		}
		hbox.getChildren().add(baudDropDown);

		MenuButton portDropDown = new MenuButton("PORT");
		// for (UsbDevice device : (List<UsbDevice>) UsbHostManager
		// .getUsbServices().getRootUsbHub().getAttachedUsbDevices()) {
		// if (device.isUsbHub() == false) {
		// UsbDeviceDescriptor descriptor = device
		// .getUsbDeviceDescriptor();
		// portDropDown.getItems().add(new MenuItem(
		// descriptor.idVendor() + " " + descriptor.idProduct()));
		// }
		// }
		hbox.getChildren().add(portDropDown);

		primary.setScene(new Scene(hbox, 400, 780));
		primary.show();
	}

	public static void main(String[] args) {
		launch(args);
	}
}

class GRemote extends PApplet {

	// GRemoteCNC - mini host interfacing with Arduino GCode Interpreter

	// Setup, event loops and state

	// Global variables
	ControlP5 cP5;

	int baudrate = 115200;

	Serial port = null;

	EnumXYZ idx = new EnumXYZ();

	int resolution = 100000; // of a unit, for fixed-point calculations;
	int position[]; // absolute
	int jog[]; // relative
	int accumulated_jog[]; // relative

	int memory[]; // absolute
	boolean memorySet[];

	float feed[];
	float feedInc = 1 * 1000; // mm
	float lastFeed = 0;

	float homing_limit[]; // absolute
	float homingInfinity = 100 * 25; // mm
	float homingFeed = 10 * 25; // mm/min

	float arc_radius = 5; // current units
	float arc_start = 0; // degrees
	float arc_end = 360; // degrees
	boolean ArcCCW = false; // direction

	int x_dir_pin = 11;
	int x_step_pin = 8;
	int x_min_pin = 22;
	int x_max_pin = 23;
	int y_dir_pin = 13;
	int y_step_pin = 12;
	int y_min_pin = 19;
	int y_max_pin = 18;
	int z_dir_pin = 9;
	int z_step_pin = 21;
	int z_min_pin = 22;
	int z_max_pin = 23;
	float x_steps_per_mm = 40.0f;
	float y_steps_per_mm = 40.0f;
	float z_steps_per_mm = 40.0f;

	// State flags
	boolean PortWasReset = false;
	boolean PortResponding = false;
	boolean WaitingForResponse = false;
	boolean HaveStringToSend = false;

	boolean SendingSequence = false;
	boolean Paused = false;
	boolean SequenceLastLineSent = false;

	boolean G_AbsoluteMode = true;
	boolean G_InchMode = false;

	boolean RapidPositioning = false;
	boolean FractionalJog = false;
	boolean HomingSetZero = true;

	boolean UI_ReloadJogDDL = false;
	boolean UI_ReloadArcTF = false;
	boolean UI_ClearGCodeTF = false;
	boolean UI_ClearFocusTF = false;

	boolean ArcMode = false;
	boolean XYZMode = false;
	boolean ZeroMode = false;
	boolean MemMode = false;

	@Override
	public void settings() {
		size(400, 780);
	}

	// setup
	@Override
	public void setup() {
		smooth();

		cP5 = new ControlP5(this);

		cP5.setColorForeground(0xff00aa00);
		cP5.setColorBackground(0xff006600);
		cP5.setColorCaptionLabel(0xffdddddd);
		cP5.setColorValueLabel(0xff88ff88);
		cP5.setColorActive(0xff00ff00);
		background(0x00000000); // background

		position = new int[idx.size()];
		jog = new int[idx.size()];
		accumulated_jog = new int[idx.size()];
		memory = new int[idx.size()];
		memorySet = new boolean[idx.size()];
		feed = new float[idx.size()];
		homing_limit = new float[idx.size()];

		// init
		for (int i = 0; i < idx.size(); i++) {
			position[i] = 0;
			jog[i] = resolution;
			accumulated_jog[i] = 0;
			memorySet[i] = false;
			feed[i] = 10 * 250; // mm/min
			homing_limit[i] = 0;
		}

		setup_console(10, 45, 380, 160);
		setup_func_buttons(10, 195);
		setup_toggles(10, 238);
		setup_jog_buttons(10, 540);

		setup_jog_controls(10, 335, 98);
		setup_homing_controls(10, 363, 70);
		setup_arc_controls(10, 391, 42);
		setup_setting_controls(10, 419, 14);

		setup_port_selector(30, 25, 180, 130);
		setup_port_led(10, 10);
	}

	// draw loop
	@Override
	public void draw() {
		update_console();
		update_port_led();
		update_toggles();
		update_func_buttons();
		update_jog_buttons();
		update_jog_controls();
		update_homing_controls();
		update_port_selector();
		update_arc_controls();
		update_setting_controls();
		update_textfields(); // manage textfields focus
		update_groups(); // manage groups
	}

	// keyboard events
	@Override
	public void keyPressed() {
		if (!(PortResponding
				&& (!SendingSequence || SendingSequence && Paused))) {
			return;
		}

		// do not process keystrokes while editing GCode
		if (((Textfield) cP5.getController("GCODE")).isFocus()) {
			return;
		}

		if (key == 'j' || key == 'J') {
			open_group('J');
		}

		if (key == 'h' || key == 'H') {
			open_group('H');
		}

		if (key == 'a' || key == 'A') {
			open_group('A');
		}

		if (key == '+') {
			feed[idx.X] += feedInc;
			feed[idx.Y] += feedInc;
		}

		if (key == '-') {
			feed[idx.X] -= feedInc;
			feed[idx.Y] -= feedInc;
		}

		if (Jogging_grp.isOpen() && key == 'x' && jog_ddl_idx[idx.X] > 0) {
			jog_ddl_idx[idx.X] -= 1;
			jog_ddl_frac[idx.X] = FractionalJog;
			jog[idx.X] = intCoord(jog_ddl_frac[idx.X]
					? jog_frac_value[jog_ddl_idx[idx.X]]
					: jog_dec_value[jog_ddl_idx[idx.X]]);
		}

		if (Jogging_grp.isOpen() && key == 'X'
				&& jog_ddl_idx[idx.X] < min(jog_frac_name.length,
						jog_dec_name.length) - 1) {
			jog_ddl_idx[idx.X] += 1;
			jog_ddl_frac[idx.X] = FractionalJog;
			jog[idx.X] = intCoord(jog_ddl_frac[idx.X]
					? jog_frac_value[jog_ddl_idx[idx.X]]
					: jog_dec_value[jog_ddl_idx[idx.X]]);
		}

		if (Jogging_grp.isOpen() && key == 'y' && jog_ddl_idx[idx.Y] > 0) {
			jog_ddl_idx[idx.Y] -= 1;
			jog_ddl_frac[idx.Y] = FractionalJog;
			jog[idx.Y] = intCoord(jog_ddl_frac[idx.Y]
					? jog_frac_value[jog_ddl_idx[idx.Y]]
					: jog_dec_value[jog_ddl_idx[idx.Y]]);
		}

		if (Jogging_grp.isOpen() && key == 'Y'
				&& jog_ddl_idx[idx.Y] < min(jog_frac_name.length,
						jog_dec_name.length) - 1) {
			jog_ddl_idx[idx.Y] += 1;
			jog_ddl_frac[idx.Y] = FractionalJog;
			jog[idx.Y] = intCoord(jog_ddl_frac[idx.Y]
					? jog_frac_value[jog_ddl_idx[idx.Y]]
					: jog_dec_value[jog_ddl_idx[idx.Y]]);
		}

		if (Jogging_grp.isOpen() && key == 'z' && jog_ddl_idx[idx.Z] > 0) {
			jog_ddl_idx[idx.Z] -= 1;
			jog_ddl_frac[idx.Z] = FractionalJog;
			jog[idx.Z] = intCoord(jog_ddl_frac[idx.Z]
					? jog_frac_value[jog_ddl_idx[idx.Z]]
					: jog_dec_value[jog_ddl_idx[idx.Z]]);
		}

		if (Jogging_grp.isOpen() && key == 'Z'
				&& jog_ddl_idx[idx.Z] < min(jog_frac_name.length,
						jog_dec_name.length) - 1) {
			jog_ddl_idx[idx.Z] += 1;
			jog_ddl_frac[idx.Z] = FractionalJog;
			jog[idx.Z] = intCoord(jog_ddl_frac[idx.Z]
					? jog_frac_value[jog_ddl_idx[idx.Z]]
					: jog_dec_value[jog_ddl_idx[idx.Z]]);
		}

		if (Jogging_grp.isOpen() && (key == 'f' || key == 'F')) {
			((Toggle) cP5.getController("fractional_jog"))
					.setValue(!FractionalJog);
		}

		if (Jogging_grp.isOpen() && (key == 'r' || key == 'R')) {
			((Toggle) cP5.getController("rapid_positioning"))
					.setValue(!RapidPositioning);
		}

		if (key == CODED && keyCode == RIGHT) {
			((Button) cP5.getController("X+")).setValue(ControlP5.PRESSED);
		}

		if (key == CODED && keyCode == LEFT) {
			((Button) cP5.getController("X-")).setValue(ControlP5.PRESSED);
		}

		if (key == CODED && keyCode == UP) {
			((Button) cP5.getController("Y+")).setValue(ControlP5.PRESSED);
		}

		if (key == CODED && keyCode == DOWN) {
			((Button) cP5.getController("Y-")).setValue(ControlP5.PRESSED);
		}

		if (key == ',') {
			((Button) cP5.getController("Z+")).setValue(ControlP5.PRESSED);
		}

		if (key == '.') {
			((Button) cP5.getController("Z-")).setValue(ControlP5.PRESSED);
		}

		if (key == DELETE) {
			((Toggle) cP5.getController("xyz_mode")).setValue(!XYZMode);
		}

		if (key == '[') {
			((Toggle) cP5.getController("arc_mode")).setValue(!ArcMode);
			open_group(ArcMode ? 'A' : 'J');
		}

		if (key == ']') {
			((Toggle) cP5.getController("zero_mode")).setValue(!ZeroMode);
		}

		if (key == ';') {
			((Toggle) cP5.getController("mem_mode")).setValue(!MemMode);
		}
	}

	// cP5 UI events
	public void controlEvent(ControlEvent event) {
		int j[] = new int[idx.size()];
		for (int i = 0; i < idx.size(); i++) {
			j[i] = 0; // clear temp array
		}

		/*
		 * because only permitted (for current state) elements are available in
		 * the UI, we assume that if we received an event, we can handle it
		 * without checking state the above is not true for keypresses, where
		 * state has to be explicitly checked before handling event
		 */

		// baud rate selected
		if (event.getName() == "BAUD") {
			baudrate = (int) event.getValue();
			println("baud=" + baudrate);
			return;
		}

		// serial port selected
		if (event.getName() == "PORT") {
			if (port != null) {
				port.stop();
			}

			try {
				port = new Serial(this, Serial.list()[(int) event.getValue()],
						baudrate);
			} catch (Exception e) {
				console_println(": can't open selected port!");
			}

			clear_console();

			PortWasReset = true;
			PortResponding = false;
			WaitingForResponse = false;
			println("port open: " + Serial.list()[(int) event.getValue()]);

			port.bufferUntil('\n');
			port.write("\r\n");
			return;
		}

		if (event.isGroup() == true) {
			if (event.getGroup().getName() == "GROUP_JOGGING") {
				if (event.getGroup().isOpen()) {
					open_group('J');
				}
			}

			if (event.getGroup().getName() == "GROUP_ARCS") {
				if (event.getGroup().isOpen()) {
					open_group('A');
				}
			}

			if (event.getGroup().getName() == "GROUP_HOMING") {
				if (event.getGroup().isOpen()) {
					open_group('H');
				}
			}

			if (event.getGroup().getName() == "GROUP_SETTING") {
				if (event.getGroup().isOpen()) {
					open_group('S');
				}
			}

			// jog setting selected
			if (event.getGroup().getName() == "JOG X") {
				int i = (int) event.getGroup().getValue();
				jog[idx.X] = intCoord(
						FractionalJog ? jog_frac_value[i] : jog_dec_value[i]);
				// println(jog[idx.X]);
				// store current jog dropdown values - workaround to enable
				// inc/dec of jog
				// values with keyboard shortcut (dropdownlist doesn't seem to
				// have .setValue)
				jog_ddl_idx[idx.X] = i;
				jog_ddl_frac[idx.X] = FractionalJog;
			}

			if (event.getGroup().getName() == "JOG Y") {
				int i = (int) event.getGroup().getValue();
				jog[idx.Y] = intCoord(
						FractionalJog ? jog_frac_value[i] : jog_dec_value[i]);
				// println(jog[idx.Y]);
				// store current jog dropdown values - workaround to enable
				// inc/dec of jog
				// values with keyboard shortcut (dropdownlist doesn't seem to
				// have .setValue)
				jog_ddl_idx[idx.Y] = i;
				jog_ddl_frac[idx.Y] = FractionalJog;
			}

			if (event.getGroup().getName() == "JOG Z") {
				int i = (int) event.getGroup().getValue();
				jog[idx.Z] = intCoord(
						FractionalJog ? jog_frac_value[i] : jog_dec_value[i]);
				// println(jog[idx.Z]);
				// store current jog dropdown values - workaround to enable
				// inc/dec of jog
				// values with keyboard shortcut (dropdownlist doesn't seem to
				// have .setValue)
				jog_ddl_idx[idx.Z] = i;
				jog_ddl_frac[idx.Z] = FractionalJog;
			}
		}

		if (event.isController() == true) {
			// print("control event from controller:
			// "+theEvent.controller().name());
			// println(", value : "+theEvent.controller().value());
			String command = "";

			// manually entered command
			if (event.getController().getName() == "GCODE") {
				command = event.getController().getStringValue().toUpperCase();
				HaveStringToSend = true; // UI_ClearFocusTF = true;
			}

			// absolute mode toggle
			if (event.getController().getName() == "absolute_mode") {
				command = ((int) event.getController().getValue() == 1)
						? "G90"
						: "G91";
				HaveStringToSend = true;
			}

			// inch mode toggle
			if (event.getController().getName() == "inch_mode") {
				command = ((int) event.getController().getValue() == 1)
						? "G20"
						: "G21";
				HaveStringToSend = true;
			}

			// send file button
			if (event.getController().getName() == "SEND FILE") {
				selectInput("Select GCode file to send", "send_file");
				// String file = selectInput("Select GCode file to send");
				// if (file == null) return;
				// send_file(file);
			}

			if (event.getController().getName() == "SAVE") {
				String[] settingData = {"Setting",
						((Textfield) cP5.getController("X_DIR_PIN")).getText(),
						((Textfield) cP5.getController("X_STEP_PIN")).getText(),
						((Textfield) cP5.getController("X_MIN_PIN")).getText(),
						((Textfield) cP5.getController("X_MAX_PIN")).getText(),
						((Textfield) cP5.getController("Y_DIR_PIN")).getText(),
						((Textfield) cP5.getController("Y_STEP_PIN")).getText(),
						((Textfield) cP5.getController("Y_MIN_PIN")).getText(),
						((Textfield) cP5.getController("Y_MAX_PIN")).getText(),
						((Textfield) cP5.getController("Z_DIR_PIN")).getText(),
						((Textfield) cP5.getController("Z_STEP_PIN")).getText(),
						((Textfield) cP5.getController("Z_MIN_PIN")).getText(),
						((Textfield) cP5.getController("Z_MAX_PIN")).getText(),
						((Textfield) cP5.getController("X_PER_MM")).getText(),
						((Textfield) cP5.getController("Y_PER_MM")).getText(),
						((Textfield) cP5.getController("X_PER_MM")).getText()};
				Properties props = System.getProperties();
				String osName = props.getProperty("os.name");
				if (osName.indexOf("Win") > -1) {
					saveStrings("setting.ini", settingData);
				} else {
					saveStrings("GRemote.app/Contents/setting.ini",
							settingData);
				}
				init_sequence();
			}

			// cancel (sending file) button
			if (event.getController().getName() == "CANCEL") {
				SendingSequence = false;
				Paused = false;
				println(": send sequence cancelled");
				console_println(": send sequence cancelled");
			}

			// pause/resume (sending file) button
			if (event.getController().getName() == "PAUSE/RESUME") {
				if (!Paused) {
					println(": send sequence paused");
					console_println(": send sequence paused");
					Paused = true;
				} else {
					println(": send sequence resumed");
					console_println(": send sequence resumed");
					Paused = false;
					send_next_line();
				}
			}

			// jog control toggles
			if (event.getController().getName() == "fractional_jog") {
				if ((int) event.getController().getValue() == 1) {
					FractionalJog = true;
				} else
					FractionalJog = false;
				UI_ReloadJogDDL = true;
			}

			if (event.getController().getName() == "rapid_positioning") {
				if ((int) event.getController().getValue() == 1)
					RapidPositioning = true;
				else {
					RapidPositioning = false;
					lastFeed = 0;
				}
			}

			if (event.getController().getName() == "FEED X") {
				feed[idx.X] = event.getController().getValue();
			}

			if (event.getController().getName() == "FEED Y") {
				feed[idx.Y] = event.getController().getValue();
			}

			if (event.getController().getName() == "FEED Z") {
				feed[idx.Z] = event.getController().getValue();
			}

			if (event.getController().getName() == "arc_mode") {
				if (XYZMode || ZeroMode || MemMode)
					return; // UI should be locked instead of state check
				if ((int) event.getController().getValue() == 1) {
					ArcMode = true;
				} else {
					ArcMode = false;
				}
				open_group(ArcMode ? 'A' : 'J');
			}

			if (event.getController().getName() == "xyz_mode") {
				if (ArcMode || ZeroMode || MemMode) {
					return; // UI should be locked instead of state check
				}

				if ((int) event.getController().getValue() == 1) {
					for (int i = 0; i < accumulated_jog.length; i++)
						accumulated_jog[i] = 0;
					XYZMode = true;
				} else {
					int jog_total = 0;
					for (int i = 0; i < accumulated_jog.length; i++)
						jog_total += abs(accumulated_jog[i]);
					if (jog_total > 0) {
						command = jog_string(accumulated_jog, G_AbsoluteMode,
								false);
						HaveStringToSend = true;
					}
					XYZMode = false;
					UI_ClearGCodeTF = true;
				}
			}

			if (event.getController().getName() == "zero_mode") {
				if (ArcMode || XYZMode || MemMode)
					return; // UI should be locked instead of state check
				if ((int) event.getController().getValue() == 1) {
					ZeroMode = true;
				} else {
					ZeroMode = false;
					UI_ClearGCodeTF = true;
				}
			}

			if (event.getController().getName() == "mem_mode") {
				if (ArcMode || XYZMode || ZeroMode)
					return; // UI should be locked instead of state check
				if ((int) event.getController().getValue() == 1) {
					MemMode = true;
				} else {
					MemMode = false;
					UI_ClearGCodeTF = true;
				}
			}

			// homing controls
			if (event.getController().getName() == "homing_set_zero") {
				if ((int) event.getController().getValue() == 1) {
					HomingSetZero = true;
				} else {
					HomingSetZero = false;
				}
			}

			// println("raw: "+homing_limit[idx.X] + ", ceil:
			// "+ceil(100*homing_limit[idx.X]) + ", floor:
			// "+floor(100*homing_limit[idx.X]) );
			if (event.getController().getName() == "homing_limit_x") {
				homing_limit[idx.X] = event.getController().getValue();
			}

			if (event.getController().getName() == "homing_limit_y") {
				homing_limit[idx.Y] = event.getController().getValue();
			}

			if (event.getController().getName() == "homing_limit_z") {
				homing_limit[idx.Z] = event.getController().getValue();
			}

			// fix for values returned by controller - they often have many
			// decimals
			// (accumulated errors from float/double additions/subtractions?)
			for (int i = 0; i < idx.size(); i++) {
				homing_limit[i] = (homing_limit[i] >= 0)
						? floor(homing_limit[i] * 100) / 100.0f
						: ceil(homing_limit[i] * 100) / 100.0f;
			}

			if (event.getController().getName() == "homing_infinity") {
				homingInfinity = event.getController().getValue();
			}

			if (event.getController().getName() == "homing_feed") {
				homingFeed = event.getController().getValue();
			}

			// home XY & Z buttons
			if (event.getController().getName() == "HOME XY") {
				homing_sequence("XY");
			}

			if (event.getController().getName() == "HOME Z") {
				homing_sequence("Z");
			}

			// arc controls
			if (event.getController().getName() == "ARC_CCW") {
				if ((int) event.getController().getValue() == 1)
					ArcCCW = true;
				else
					ArcCCW = false;
			}

			if (event.getController().getName() == "ARC_RADIUS") {
				float f = arc_radius;
				arc_radius = isFloat(event.getController().getStringValue())
						? Float.parseFloat(
								event.getController().getStringValue())
						: arc_radius;
				if (arc_radius <= 0)
					arc_radius = f;
				UI_ReloadArcTF = true;
				UI_ClearFocusTF = true;
			}

			if (event.getController().getName() == "ARC_START") {
				float f = arc_start;
				arc_start = isFloat(event.getController().getStringValue())
						? Float.parseFloat(
								event.getController().getStringValue())
						: arc_start;
				if (arc_start < 0 || arc_start >= 360 || arc_start >= arc_end)
					arc_start = f;
				UI_ReloadArcTF = true;
				UI_ClearFocusTF = true;
			}

			if (event.getController().getName() == "ARC_END") {
				float f = arc_end;
				arc_end = isFloat(event.getController().getStringValue())
						? Float.parseFloat(
								event.getController().getStringValue())
						: arc_end;
				if (arc_end <= 0 || arc_end > 360 || arc_start >= arc_end)
					arc_end = f;
				UI_ReloadArcTF = true;
				UI_ClearFocusTF = true;
			}

			int arc_offset;

			// jog button events
			if (event.getController().getName() == "X+") {
				j[idx.X] = jog[idx.X];
				if (XYZMode) {
					accumulated_jog[idx.X] += jog[idx.X];
					return;
				}
				if (ZeroMode)
					j[idx.X] = -position[idx.X];
				if (MemMode)
					if (memorySet[idx.X])
						j[idx.X] = memory[idx.X] - position[idx.X];
					else
						return;
				if (ArcMode) {
					arc_offset = ArcCCW ? -90 : 90;
					command = arc_string(ArcCCW, arc_radius,
							arc_offset + (ArcCCW ? arc_start : -arc_start),
							arc_offset + (ArcCCW ? arc_end : -arc_end),
							G_AbsoluteMode);
					HaveStringToSend = true;
				} else if (j[idx.X] != 0) {
					command = jog_string(j, G_AbsoluteMode, false);
					HaveStringToSend = true;
				}
				if (ZeroMode) {
					ZeroMode = false;
					UI_ClearGCodeTF = true;
				}
				if (MemMode) {
					MemMode = false;
					UI_ClearGCodeTF = true;
				}
			}

			if (event.getController().getName() == "X-") {
				j[idx.X] = -jog[idx.X];
				if (XYZMode) {
					accumulated_jog[idx.X] -= jog[idx.X];
					return;
				}
				if (MemMode) {
					memory[idx.X] = position[idx.X];
					memorySet[idx.X] = true;
					return;
				}
				if (ArcMode) {
					arc_offset = ArcCCW ? 90 : -90;
					command = arc_string(ArcCCW, arc_radius,
							arc_offset + (ArcCCW ? arc_start : -arc_start),
							arc_offset + (ArcCCW ? arc_end : -arc_end),
							G_AbsoluteMode);
					HaveStringToSend = true;
				} else {
					command = ZeroMode
							? "G92 X0"
							: jog_string(j, G_AbsoluteMode, false);
					HaveStringToSend = true;
				}
				// if (ZeroMode) { ZeroMode = false; UI_ClearGCodeTF = true; }
			}

			if (event.getController().getName() == "Y+") {
				j[idx.Y] = jog[idx.Y];
				if (XYZMode) {
					accumulated_jog[idx.Y] += jog[idx.Y];
					return;
				}
				if (ZeroMode)
					j[idx.Y] = -position[idx.Y];
				if (MemMode)
					if (memorySet[idx.Y])
						j[idx.Y] = memory[idx.Y] - position[idx.Y];
					else
						return;
				if (ArcMode) {
					arc_offset = ArcCCW ? 0 : 180;
					command = arc_string(ArcCCW, arc_radius,
							arc_offset + (ArcCCW ? arc_start : -arc_start),
							arc_offset + (ArcCCW ? arc_end : -arc_end),
							G_AbsoluteMode);
					HaveStringToSend = true;
				} else if (j[idx.Y] != 0) {
					command = jog_string(j, G_AbsoluteMode, false);
					HaveStringToSend = true;
				}
				if (ZeroMode) {
					ZeroMode = false;
					UI_ClearGCodeTF = true;
				}
				if (MemMode) {
					MemMode = false;
					UI_ClearGCodeTF = true;
				}
			}

			if (event.getController().getName() == "Y-") {
				j[idx.Y] = -jog[idx.Y];
				if (XYZMode) {
					accumulated_jog[idx.Y] -= jog[idx.Y];
					return;
				}
				if (MemMode) {
					memory[idx.Y] = position[idx.Y];
					memorySet[idx.Y] = true;
					return;
				}
				if (ArcMode) {
					arc_offset = ArcCCW ? -180 : 0;
					command = arc_string(ArcCCW, arc_radius,
							arc_offset + (ArcCCW ? arc_start : -arc_start),
							arc_offset + (ArcCCW ? arc_end : -arc_end),
							G_AbsoluteMode);
					HaveStringToSend = true;
				} else {
					command = ZeroMode
							? "G92 Y0"
							: jog_string(j, G_AbsoluteMode, false);
					HaveStringToSend = true;
				}
				// if (ZeroMode) { ZeroMode = false; UI_ClearGCodeTF = true; }
			}

			if (event.getController().getName() == "Z+") {
				j[idx.Z] = jog[idx.Z];
				if (XYZMode) {
					accumulated_jog[idx.Z] += jog[idx.Z];
					return;
				}
				if (ZeroMode)
					j[idx.Z] = -position[idx.Z];
				if (MemMode)
					if (memorySet[idx.Z])
						j[idx.Z] = memory[idx.Z] - position[idx.Z];
					else
						return;
				if (j[idx.Z] != 0) {
					command = jog_string(j, G_AbsoluteMode, false);
					HaveStringToSend = true;
				}

				if (ZeroMode) {
					ZeroMode = false;
					UI_ClearGCodeTF = true;
				}
				if (MemMode) {
					MemMode = false;
					UI_ClearGCodeTF = true;
				}
			}

			if (event.getController().getName() == "Z-") {
				j[idx.Z] = -jog[idx.Z];
				if (XYZMode) {
					accumulated_jog[idx.Z] -= jog[idx.Z];
					return;
				}
				if (MemMode) {
					memory[idx.Z] = position[idx.Z];
					memorySet[idx.Z] = true;
					return;
				}
				command = ZeroMode
						? "G92 Z0"
						: jog_string(j, G_AbsoluteMode, false);
				HaveStringToSend = true;
				// if (ZeroMode) { ZeroMode = false; UI_ClearGCodeTF = true; }
			}

			if (HaveStringToSend) {
				HaveStringToSend = false;

				if (WaitingForResponse) {
					delay(50);
				} // wait a bit

				if (WaitingForResponse) {
					java.awt.Toolkit.getDefaultToolkit().beep();
					return;
				} // beep & exit if still no response from port

				process_command(command);
			}
		}
	}

	// serial events
	public void serialEvent(Serial port) {
		// if(!SendingSequence) delay(100);
		delay(10);
		String s = port.readString();
		println("=> " + s.trim());
		console_println("> " + s.trim());
		s = s.trim().toUpperCase();

		// process response
		// start/ok line-based protocol is supported by most reprap firmware

		// firmware reset (not all firmware does this though)
		if (s.equals("START")) {
			println("firmware start, sending init sequence");
			SendingSequence = false;
			Paused = false;
			PortWasReset = false;
			PortResponding = true;
			WaitingForResponse = false;
			for (int i = 0; i < idx.size(); i++)
				position[i] = 0; // zero position
			init_sequence();
			return;
		}

		// response to a command
		if (s.equals("OK")) {
			WaitingForResponse = false; // let everyone know they can send more
										// to the port
			if (SendingSequence && !Paused)
				send_next_line();
			if (SendingSequence && SequenceLastLineSent) {
				SequenceLastLineSent = false;
				SendingSequence = false;
				println(": done sending sequence");
				console_println(": done sending sequence");
			}
			return;
		}

		/*
		 * if we received something other than OK or START after port reset,
		 * assume port is responding kludgy but should work for firmware that
		 * supports 'ok' but not 'start' e.g. Grbl (as of 0.7d)
		 */
		if (PortWasReset) {
			println("port reset, sending init sequence");
			PortWasReset = false;
			PortResponding = true;
			init_sequence();
		}
	}

	// business logic

	// Storage for sending a file - should be a class containing GCodeCommand[]?
	String[] gcode_sequence;
	int next_gcode_line = 0;

	class GCodeCommand {
		EnumAZ idx = new EnumAZ();
		public int G = (int) 'G';
		public int M = (int) 'M';
		public int NOP = (int) ' ';
		public int command = 0;
		public int code = 0;

		public boolean has[];
		public float value[];

		public GCodeCommand() {
			init();
		}

		public GCodeCommand(String s) {
			init();
			parse(s);
		}

		public GCodeCommand(char command, int code) {
			init();
			if (command == 'G' || command == 'M') {
				this.command = (int) command;
				this.code = code;
			}
		}

		public void init() {
			command = this.NOP;
			has = new boolean[this.idx.size()];
			value = new float[this.idx.size()];
			for (int i = 0; i < has.length; i++)
				this.has[i] = false;
		}

		public void set(int idx, float value) {
			this.value[idx] = value;
			this.has[idx] = true;
		}

		public String render() {
			String out = "";
			int renderFirst[] = {idx.X, idx.Y, idx.Z, idx.I, idx.J, idx.K,
					idx.F};
			boolean RenderedYet[] = new boolean[idx.size()];
			for (int i = 0; i < RenderedYet.length; i++)
				RenderedYet[i] = false;

			if (this.command == this.G) {
				out = "G" + this.code;
				RenderedYet[idx.G] = true;
			}

			if (this.command == this.M) {
				out = "M" + this.code;
				RenderedYet[idx.M] = true;
			}

			for (int i = 0; i < renderFirst.length; i++) {
				if (this.has[renderFirst[i]]) {
					out += (" " + idx.strVal[renderFirst[i]]
							+ this.value[renderFirst[i]]);
					RenderedYet[renderFirst[i]] = true;
				}
			}

			for (int i = 0; i < RenderedYet.length; i++) {
				if (this.has[i] && !RenderedYet[i]) {
					out += (" " + idx.strVal[i] + this.value[i]);
					RenderedYet[i] = true;
				}
			}

			return out;
		}

		public void parse(String cmd) {
			StringTokenizer st = new StringTokenizer(cmd);
			String token = "";
			while (st.hasMoreTokens()) {
				token = st.nextToken();
				// println(token);
				if (token.substring(0, 1).toUpperCase().equals("G")) {
					this.command = (int) 'G';
					this.code = Integer.parseInt(token.substring(1));
				}
				if (token.substring(0, 1).toUpperCase().equals("M")) {
					this.command = (int) 'M';
					this.code = Integer.parseInt(token.substring(1));
				}
				// there could be other commands e.g. F10 set feedrate
				if (this.command != this.NOP)
					for (int i = 0; i < this.idx.size(); i++)
						if (token.substring(0, 1).toUpperCase()
								.equals(this.idx.strVal[i])) {
							this.has[i] = true;
							this.value[i] = Float
									.parseFloat(token.substring(1));
						}
			}
		}

	}

	// Logic related functions

	// file sending handler
	// void send_file(String f) {
	public void send_file(File f) {
		if (f == null)
			return;
		gcode_sequence = loadStrings(f);
		if (gcode_sequence == null) {
			println(": unable to open the file");
			console_println(": unable to open the file");
			return;
		}
		// set up state and send first line
		SendingSequence = true;
		next_gcode_line = 0;
		send_next_line();
	}

	// send next line of gcode_sequence indexed by next_gcode_line
	// subsequent send_next_line() will be called by eventSerial upon receiving
	// 'ok' response and looking at the state
	public void send_next_line() {
		// check state
		if (!SendingSequence || WaitingForResponse)
			return;

		// check EOF and skip empty lines
		while (true) {
			if (gcode_sequence.length == next_gcode_line) { // EOF
				SequenceLastLineSent = true;
				// SendingSequence = false;
				// println("Done sending sequence"); console_println("Done
				// sending sequence");
				return;
			}
			int startCmtIdx = gcode_sequence[next_gcode_line].indexOf('(');
			int endCmtIdx = gcode_sequence[next_gcode_line].indexOf(')');
			if (startCmtIdx > -1 && endCmtIdx > -1) {
				gcode_sequence[next_gcode_line] = gcode_sequence[next_gcode_line]
						.substring(0, startCmtIdx);

			}
			if (gcode_sequence[next_gcode_line].trim().equals(""))
				next_gcode_line++; // skip empty
			else
				break;
		}
		// process command
		String s = gcode_sequence[next_gcode_line].trim().toUpperCase();
		process_command(s);
		// if (proceed) { port.write(s); println("<= "+s); console_println("<
		// "+s); }
		next_gcode_line++;
	}

	// process command to be sent to firmware and adjust state accordingly
	// return true to proceed sending command to firmware, false otherwise
	// if false is returned, command will be lost unless logic is somehow stores
	// it to send at a later time
	public void process_command(String s) {
		GCodeCommand cmd = new GCodeCommand(s);
		EnumXYZ xyz = new EnumXYZ();
		// println("cmd: " + cmd.action + cmd.code);

		if (cmd.command == cmd.G) {
			switch (cmd.code) {
				// Parse GCode and adjust current position (limited by current
				// resolution)
				case 0 :
				case 1 :
				case 2 :
				case 3 :
					for (int i = 0; i < xyz.size(); i++)
						if (cmd.has[xyz.toAZ(xyz.X + i)])
							position[xyz.X + i] = G_AbsoluteMode
									? intCoord(cmd.value[xyz.toAZ(xyz.X + i)])
									: (position[xyz.X + i] + intCoord(
											cmd.value[xyz.toAZ(xyz.X + i)]));
					break;

				// Intercept absolute and inch mode commands and change state
				// Return false to avoid duplicate command from toggles
				// triggered by state
				// change, unless state doesn't change
				case 20 :
				case 21 :
					// set waiting state early to avoid triggering UI event and
					// duplicate command
					WaitingForResponse = true;
					boolean Inches = G_InchMode;
					G_InchMode = (cmd.code == 20) ? true : false;
					if (Inches != G_InchMode) {
						if (G_InchMode) {
							feedInc /= 25;
							feed[xyz.X] /= 25;
							feed[xyz.Y] /= 25;
							feed[xyz.Z] /= 25;
							homingInfinity /= 25;
							homingFeed /= 25;
						} else {
							feedInc *= 25;
							feed[xyz.X] *= 25;
							feed[xyz.Y] *= 25;
							feed[xyz.Z] *= 25;
							homingInfinity *= 25;
							homingFeed *= 25;
						}
					}
					break;
				case 90 :
				case 91 :
					// set waiting state early to avoid triggering UI event and
					// duplicate command
					WaitingForResponse = true;
					G_AbsoluteMode = (cmd.code == 90) ? true : false;
					break;

				case 92 :
					for (int i = 0; i < xyz.size(); i++)
						if (cmd.has[xyz.toAZ(xyz.X + i)])
							position[xyz.X + i] = intCoord(
									cmd.value[xyz.toAZ(xyz.X + i)]); // value
																		// should
																		// be 0;
					break;

			}
			// println("position = X"+floatCoord(position[xyz.X])+"
			// Y"+floatCoord(position[xyz.Y])+" Z"+floatCoord(position[xyz.Z]));
		}

		// write string to port and consoles
		try {
			port.write(s + "\r\n");
			println("<= " + s);
			console_println("< " + s);
			// set waiting state
			WaitingForResponse = true;
			// Todo: intercept tool change commands and enter Paused state
		} catch (Exception e) {
		}

	}

	// initial sequence following port selection to align host state with
	// firmware state
	public void init_sequence() {
		String[] s = {};

		s = append(s, String.format("$1 X%s Y%s Z%s",
				((Textfield) cP5.getController("X_STEP_PIN")).getText(),
				((Textfield) cP5.getController("Y_STEP_PIN")).getText(),
				((Textfield) cP5.getController("Z_STEP_PIN")).getText()));
		s = append(s, String.format("$2 X%s Y%s Z%s",
				((Textfield) cP5.getController("X_DIR_PIN")).getText(),
				((Textfield) cP5.getController("Y_DIR_PIN")).getText(),
				((Textfield) cP5.getController("Z_DIR_PIN")).getText()));
		s = append(s, String.format("$3 X%s Y%s Z%s",
				((Textfield) cP5.getController("X_MIN_PIN")).getText(),
				((Textfield) cP5.getController("Y_MIN_PIN")).getText(),
				((Textfield) cP5.getController("Z_MIN_PIN")).getText()));
		s = append(s, String.format("$4 X%s Y%s Z%s",
				((Textfield) cP5.getController("X_MAX_PIN")).getText(),
				((Textfield) cP5.getController("Y_MAX_PIN")).getText(),
				((Textfield) cP5.getController("Z_MAX_PIN")).getText()));
		s = append(s,
				String.format("$6 X%s Y%s Z%s",
						((Textfield) cP5.getController("X_PER_MM")).getText(),
						((Textfield) cP5.getController("Y_PER_MM")).getText(),
						((Textfield) cP5.getController("Z_PER_MM")).getText()));
		s = append(s, G_AbsoluteMode ? "G90" : "G91");
		s = append(s, G_InchMode ? "G20" : "G21");

		gcode_sequence = s;
		// set up state and send first line
		SendingSequence = true;
		next_gcode_line = 0;
		send_next_line();
	}

	// homing sequence
	public void homing_sequence(String axes) {
		String[] seq = {};
		String s1 = "G0";
		String s2 = "G0";
		String s3 = "G1";
		String s4 = "G92";
		int axis_min, axis_max;
		boolean Absolute = G_AbsoluteMode;

		// check if limits are 0
		if (axes == "XY") {
			if (homing_limit[idx.X] == 0 && homing_limit[idx.Y] == 0) {
				println(": limit <|>0 homes to min|max");
				console_println(": limit <|>0 homes to min|max");
				return;
			}
			axis_min = 0;
			axis_max = 1;
		} else { // if axes == "Z"
			if (homing_limit[idx.Z] == 0) {
				println(": limit <|>0 homes to min|max");
				console_println(": limit <|>0 homes to min|max");
				return;
			}
			axis_min = 2;
			axis_max = 2;
		}

		// Always do homing in relative mode
		if (Absolute)
			seq = append(seq, "G91");

		for (int i = axis_min; i <= axis_max; i++) {
			// rapid positioning to infinity, rapid stop when limit switches are
			// hit
			if (homing_limit[i] != 0)
				s1 += " " + idx.strVal[i]
						+ (homing_limit[i] > 0
								? homingInfinity
								: -homingInfinity);
			// rapid move to future zero
			if (homing_limit[i] != 0)
				s2 += " " + idx.strVal[i] + (-homing_limit[i]);
			// slow move aiming to overshoot limit switches by x2; stop exactly
			// at limit
			// switches
			if (homing_limit[i] != 0)
				s3 += " " + idx.strVal[i] + 2 * homing_limit[i];
			// set zero if requested
			if (homing_limit[i] != 0)
				s4 += " " + idx.strVal[i] + "0";
		}

		// compose and run sequence
		seq = append(seq, s1);
		seq = append(seq, s2);
		seq = append(seq, s3 + " F" + homingFeed);
		seq = append(seq, s2);
		seq = append(seq, s4);

		// restore absolute mode when done
		if (Absolute)
			seq = append(seq, "G90");

		gcode_sequence = seq;
		// set up state and send first line
		SendingSequence = true;
		next_gcode_line = 0;
		send_next_line();
	}

	public String jog_string(int jog[], boolean Absolute, boolean RenderZero) {
		EnumXYZ xyz = new EnumXYZ();
		String gcode = RapidPositioning ? "G0" : "G1";
		float minFeed = 0;
		int totaljog = 0;

		for (int i = 0; i < feed.length; i++)
			minFeed += feed[i];

		for (int i = 0; i < jog.length; i++)
			if (jog[i] != 0 || RenderZero) {
				gcode += (" " + xyz.strVal[i]
						+ floatCoord(Absolute ? jog[i] + position[i] : jog[i]));
				totaljog += abs(jog[i]);
				if (feed[i] < minFeed)
					minFeed = feed[i];
			}

		if (totaljog == 0 && !RenderZero)
			return "";

		if (!RapidPositioning && (minFeed != lastFeed))
			gcode += (" F" + minFeed);

		return gcode;
	}

	public String arc_string(boolean CCW, float R, float start, float end,
			boolean Absolute) {
		String gcode = CCW ? "G3" : "G2";
		int start_xy[] = {0, 0};
		int end_xy[] = {0, 0};

		start_xy = intCoord(polar2cartesian(R, start));
		end_xy = intCoord(polar2cartesian(R, end));
		// println("Arc start: "+Arrays.toString(start_xy));
		// println("Arc end: "+Arrays.toString(end_xy));

		// I,J = -start_xy (I,J are always relative)
		// X,Y = end_xy - start_xy (relative)
		gcode += " X" + floatCoord(end_xy[idx.X] - start_xy[idx.X]
				+ (Absolute ? position[idx.X] : 0));
		gcode += " Y" + floatCoord(end_xy[idx.Y] - start_xy[idx.Y]
				+ (Absolute ? position[idx.Y] : 0));
		gcode += " I" + floatCoord(-start_xy[idx.X]);
		gcode += " J" + floatCoord(-start_xy[idx.Y]);

		float minFeed = 0;
		for (int i = 0; i < feed.length; i++)
			minFeed += feed[i];

		for (int i = 0; i < 2; i++)
			if (feed[i] < minFeed)
				minFeed = feed[i];

		if (!RapidPositioning && (minFeed != lastFeed))
			gcode += (" F" + minFeed);

		// println(gcode);
		return gcode;
	}

	public String zero_string() {
		String s = "ZERO:";
		s += " X" + (G_AbsoluteMode ? "0.0" : floatCoord(-position[idx.X]));
		s += " Y" + (G_AbsoluteMode ? "0.0" : floatCoord(-position[idx.Y]));
		s += " Z" + (G_AbsoluteMode ? "0.0" : floatCoord(-position[idx.Z]));
		return s;
	}

	public String mem_string() {
		String s = "MEM:";
		s += memorySet[idx.X]
				? (" X" + floatCoord(G_AbsoluteMode
						? memory[idx.X]
						: memory[idx.X] - position[idx.X]))
				: "";
		s += memorySet[idx.Y]
				? (" Y" + floatCoord(G_AbsoluteMode
						? memory[idx.Y]
						: memory[idx.Y] - position[idx.Y]))
				: "";
		s += memorySet[idx.Z]
				? (" Z" + floatCoord(G_AbsoluteMode
						? memory[idx.Z]
						: memory[idx.Z] - position[idx.Z]))
				: "";
		return s;
	}

	// UI and controlP5 related functions
	//
	// Todo: build update functions for all buttons and toggles, dependent
	// solely on state flags.

	Textarea console_ta;
	int console_size = 10;
	int next_line = 0;
	String[] console = new String[console_size];
	String[] ord_console = new String[console_size];
	String[] jog_buttons = {"X+", "X-", "Y+", "Y-", "Z+", "Z-"};
	String[] jog_toggles = {"arc_mode", "xyz_mode", "zero_mode", "mem_mode"};
	String[] jog_frac_name = {"1/32", "1/16", "1/8", "1/4", "1/2", "1"};
	Float[] jog_frac_value = {0.03125f, 0.0625f, 0.125f, 0.25f, 0.5f, 1.0f};
	String[] jog_dec_name = {"0.001", "0.01", "0.1", "1", "10", "100"};
	Float[] jog_dec_value = {0.001f, 0.01f, 0.1f, 1.0f, 10.0f, 100.0f};
	Integer[] baud_values = {9600, 19200, 38400, 57600, 115200};
	DropdownList jogX_ddl, jogY_ddl, jogZ_ddl;
	ControlGroup<?> Jogging_grp;
	ControlGroup<?> Homing_grp;
	ControlGroup<?> Arcs_grp;
	ControlGroup<?> Setting_grp;
	int[] jog_ddl_idx;
	boolean[] jog_ddl_frac;

	public void clear_console() {
		for (int i = 0; i < console_size; i++) {
			console[i] = "";
			ord_console[i] = "";
		}
	}

	// console functions
	public void setup_console(int x1, int y1, int x2, int y2) {
		console_ta = cP5.addTextarea("CONSOLE", "", x1, y1, x2, y2);

		clear_console();

		ord_console[0] = "Select serial port";
		cP5.addTextfield("GCODE", x1, y1 + 110, 380, 20).setLabel("");

		cP5.addTextlabel("X_POS", "", x1, y1 + 135);
		cP5.addTextlabel("Y_POS", "", x1 + 50, y1 + 135);
		cP5.addTextlabel("Z_POS", "", x1 + 100, y1 + 135);
	}

	public void console_println(String line) {
		// add to buffer
		console[next_line] = line;

		if (next_line < console_size - 1) {
			next_line++;
		} else {
			next_line = 0;
		}

		// reorder console array into ord_console array
		int j = 0;
		int k = next_line;
		for (int i = k; i < console_size; i++) {
			ord_console[j] = console[i];
			j++;
		}
		for (int i = 0; i < k; i++) {
			ord_console[j] = console[i];
			j++;
		}
	}

	public void update_console() {
		console_ta.setText(join(ord_console, '\n'));

		for (int i = 0; i < idx.size(); i++) {
			cP5.getController(idx.strVal[i] + "_POS")
					.setVisible(PortResponding);
			((Textlabel) cP5.getController(idx.strVal[i] + "_POS"))
					.setValue(idx.strVal[i] + floatCoord(position[i]));
		}

		if (port == null || SendingSequence && !Paused) {
			cP5.getController("GCODE").setVisible(false);
		} else {
			cP5.getController("GCODE").setVisible(true);

			if (UI_ClearGCodeTF == true) {
				((Textfield) cP5.getController("GCODE")).setText("");
				UI_ClearGCodeTF = false;
			}

			if (XYZMode == true) {
				((Textfield) cP5.getController("GCODE")).setText(
						jog_string(accumulated_jog, G_AbsoluteMode, true));
			}

			if (ZeroMode == true) {
				((Textfield) cP5.getController("GCODE")).setText(zero_string());
			}

			if (MemMode == true) {
				((Textfield) cP5.getController("GCODE")).setText(mem_string());
			}

			cP5.getController("GCODE").setLock(XYZMode || ZeroMode || MemMode);
		}
	}

	// toggle functions
	public void setup_toggles(int x, int y) {
		Toggle toggle;

		toggle = cP5.addToggle("sending_LED").setValue(false).setPosition(x, y)
				.setSize(14, 14).setLabel("SENDING").setLock(true)
				.setColorBackground(color(0, 80, 0))
				.setColorActive(color(0, 255, 0));
		toggle.getCaptionLabel().getStyle().marginTop = -14;
		toggle.getCaptionLabel().getStyle().marginLeft = 18;

		toggle = cP5.addToggle("paused_LED").setValue(false)
				.setPosition(x, y + 20).setSize(14, 14).setLabel("PAUSED")
				.setLock(true).setColorBackground(color(80, 80, 0))
				.setColorActive(color(255, 255, 0));
		toggle.getCaptionLabel().getStyle().marginTop = -14;
		toggle.getCaptionLabel().getStyle().marginLeft = 18;

		toggle = cP5.addToggle("waiting_LED").setValue(false)
				.setPosition(x, y + 40).setSize(14, 14).setLabel("WAITING")
				.setLock(true).setColorBackground(color(80, 0, 0))
				.setColorActive(color(255, 0, 0));
		toggle.getCaptionLabel().getStyle().marginTop = -14;
		toggle.getCaptionLabel().getStyle().marginLeft = 18;

		toggle = cP5.addToggle("absolute_mode").setPosition(x + 75, y)
				.setSize(14, 14).setLabel("ABSOLUTE");
		toggle.getCaptionLabel().getStyle().marginTop = -14;
		toggle.getCaptionLabel().getStyle().marginLeft = 18;

		toggle = cP5.addToggle("inch_mode").setPosition(x + 75, y + 20)
				.setSize(14, 14).setLabel("INCHES");
		toggle.getCaptionLabel().getStyle().marginTop = -14;
		toggle.getCaptionLabel().getStyle().marginLeft = 18;
	}

	public void update_toggles() {
		// set visibility
		cP5.getController("sending_LED").setVisible(PortResponding);
		cP5.getController("paused_LED").setVisible(PortResponding);
		cP5.getController("waiting_LED").setVisible(PortResponding);
		cP5.getController("absolute_mode").setVisible(PortResponding);
		cP5.getController("inch_mode").setVisible(PortResponding);

		// set lock
		cP5.getController("absolute_mode").setLock(SendingSequence && !Paused);
		cP5.getController("inch_mode").setLock(SendingSequence && !Paused);

		// set values
		if ((int) cP5.getController("sending_LED")
				.getValue() != (SendingSequence ? 1 : 0)) {
			cP5.getController("sending_LED")
					.setValue((SendingSequence ? 1 : 0));
		}

		if ((int) cP5.getController("paused_LED")
				.getValue() != (Paused ? 1 : 0)) {
			cP5.getController("paused_LED").setValue((Paused ? 1 : 0));
		}

		if ((int) cP5.getController("waiting_LED")
				.getValue() != (WaitingForResponse ? 1 : 0)) {
			cP5.getController("waiting_LED")
					.setValue((WaitingForResponse ? 1 : 0));
		}

		if ((int) cP5.getController("absolute_mode")
				.getValue() != (G_AbsoluteMode ? 1 : 0)) {
			cP5.getController("absolute_mode")
					.setValue((G_AbsoluteMode ? 1 : 0));
		}

		if ((int) cP5.getController("inch_mode")
				.getValue() != (G_InchMode ? 1 : 0)) {
			cP5.getController("inch_mode").setValue((G_InchMode ? 1 : 0));
		}
	}

	// port selection functions
	public void setup_port_selector(int x, int y, int x2, int y2) {
		// DropdownList baud_ddl = cP5.addDropdownList("BAUD", x, y + 10, 80,
		// 180)
		// .setBarHeight(28).setOpen(false);
		// baud_ddl.getCaptionLabel().set("115200");
		// baud_ddl.getCaptionLabel().getFont().setSize(14);
		// baud_ddl.getCaptionLabel().getStyle().marginTop = 8;
		// for (int i = 0; i < baud_values.length; i++) {
		// baud_ddl.addItem(baud_values[i].toString(), baud_values[i]);
		// }

//		DropdownList ports_ddl = cP5
//				.addDropdownList("PORT", x + 90, y + 10, 274, 340)
//				.setBarHeight(28).setItemHeight(28).setOpen(false);
//		ports_ddl.getCaptionLabel().set("PORT");
//		ports_ddl.getCaptionLabel().getFont().setSize(14);
//		ports_ddl.getCaptionLabel().getStyle().marginTop = 8;
//		for (int i = 0; i < Serial.list().length; i++) {
//			ports_ddl.addItem(Serial.list()[i], i);
//		}

		ControlFont.sharp();
	}

	public void setup_port_led(int x, int y) {
		Toggle toggle = cP5.addToggle("port_LED").setPosition(x, y)
				.setSize(14, 14).setLabel("").setLock(true);
		toggle.setColorBackground(color(0, 0, 127));
		toggle.setColorActive(color(0, 0, 255));
	}

	public void update_port_led() {
		int c = 0;

		if (port != null)
			c = port.available();

		if (c > 1)
			c = 1;

		if ((int) cP5.getController("port_LED").getValue() != c)
			cP5.getController("port_LED").setValue(c);
	}

	public void update_port_selector() {
		try {
			cP5.getGroup("PORT").setVisible(!SendingSequence);
		} catch (NullPointerException exception) {
		}

		try {
			cP5.getGroup("BAUD").setVisible(!SendingSequence);
		} catch (NullPointerException exception) {
		}
	}

	// buttons

	public void setup_func_buttons(int x, int y) {
		Button button;

		button = cP5.addButton("SEND FILE").setPosition(x, y).setSize(88, 30);
		button.getCaptionLabel().setSize(14);
		button.getCaptionLabel().getStyle().marginLeft = 10;

		button = cP5.addButton("PAUSE/RESUME").setPosition(x + 92, y)
				.setSize(88, 30);
		button.getCaptionLabel().setSize(14);
		button.getCaptionLabel().getStyle().marginLeft = 15;

		button = cP5.addButton("CANCEL").setPosition(x + 184, y).setSize(88,
				30);
		button.getCaptionLabel().setSize(14);
		button.getCaptionLabel().getStyle().marginLeft = 15;
	}

	public void update_func_buttons() {
		if (PortResponding == false) {
			cP5.getController("SEND FILE").setVisible(false);
			cP5.getController("PAUSE/RESUME").setVisible(false);
			cP5.getController("CANCEL").setVisible(false);

			return;
		}

		cP5.getController("SEND FILE").setVisible(!SendingSequence);
		cP5.getController("CANCEL").setVisible(SendingSequence);
		cP5.getController("PAUSE/RESUME").setVisible(SendingSequence);

		if (Paused == true) {
			cP5.getController("PAUSE/RESUME").setLabel("RESUME");
		} else {
			cP5.getController("PAUSE/RESUME").setLabel("PAUSE");
		}
	}

	public void setup_jog_buttons(int x, int y) {
		Toggle toggle;

		toggle = cP5.addToggle("arc_mode").setPosition(x, y).setSize(122, 60)
				.setLabel("ARCS");
		toggle.getCaptionLabel().getStyle().marginTop = -31;
		toggle.getCaptionLabel().getStyle().marginLeft = 14;
		toggle.getCaptionLabel().getFont().setSize(14);

		toggle = cP5.addToggle("xyz_mode").setPosition(x, y + 66)
				.setSize(122, 60).setLabel("XYZ");
		toggle.getCaptionLabel().getStyle().marginTop = -31;
		toggle.getCaptionLabel().getStyle().marginLeft = 14;
		toggle.getCaptionLabel().getFont().setSize(14);

		toggle = cP5.addToggle("zero_mode").setPosition(x + 128, y)
				.setSize(122, 60).setLabel("ZERO");
		toggle.getCaptionLabel().getStyle().marginTop = -31;
		toggle.getCaptionLabel().getStyle().marginLeft = 14;
		toggle.getCaptionLabel().getFont().setSize(14);

		toggle = cP5.addToggle("mem_mode").setPosition(x + 128, y + 66)
				.setSize(122, 60).setLabel("MEM");
		toggle.getCaptionLabel().getStyle().marginTop = -31;
		toggle.getCaptionLabel().getStyle().marginLeft = 14;
		toggle.getCaptionLabel().getFont().setSize(14);

		Button button;

		button = cP5.addButton("Z+").setPosition(x + 256, y).setSize(122, 60);
		button.getCaptionLabel().getStyle().marginTop = 10;
		button.getCaptionLabel().getStyle().marginLeft = 3;
		button.getCaptionLabel().getFont().setSize(14);

		button = cP5.addButton("Z-").setPosition(x + 256, y + 66).setSize(122,
				60);
		button.getCaptionLabel().getStyle().marginTop = 10;
		button.getCaptionLabel().getStyle().marginLeft = 3;
		button.getCaptionLabel().getFont().setSize(14);

		button = cP5.addButton("Y+").setPosition(x + 128, y + 134).setSize(122,
				48);
		button.getCaptionLabel().getStyle().marginTop = 10;
		button.getCaptionLabel().getStyle().marginLeft = 3;
		button.getCaptionLabel().getFont().setSize(14);

		button = cP5.addButton("Y-").setPosition(x + 128, y + 190).setSize(122,
				48);
		button.getCaptionLabel().getStyle().marginTop = 10;
		button.getCaptionLabel().getStyle().marginLeft = 3;
		button.getCaptionLabel().getFont().setSize(14);

		button = cP5.addButton("X-").setPosition(x, y + 190).setSize(122, 48);
		button.getCaptionLabel().getStyle().marginTop = 10;
		button.getCaptionLabel().getStyle().marginLeft = 3;
		button.getCaptionLabel().getFont().setSize(14);

		button = cP5.addButton("X+").setPosition(x + 256, y + 190).setSize(122,
				48);
		button.getCaptionLabel().getStyle().marginTop = 10;
		button.getCaptionLabel().getStyle().marginLeft = 3;
		button.getCaptionLabel().getFont().setSize(14);

		for (int i = 0; i < jog_buttons.length; i++) {
			((Button) cP5.getController(jog_buttons[i]))
					.activateBy(ControlP5.PRESSED);
		}
	}

	public void update_jog_buttons() {
		Boolean visible = PortResponding
				&& (!SendingSequence || SendingSequence && Paused);

		if ((int) cP5.getController("arc_mode").getValue() != (ArcMode ? 1 : 0))
			cP5.getController("arc_mode").setValue((ArcMode ? 1 : 0));

		if ((int) cP5.getController("xyz_mode").getValue() != (XYZMode ? 1 : 0))
			cP5.getController("xyz_mode").setValue((XYZMode ? 1 : 0));

		if ((int) cP5.getController("zero_mode")
				.getValue() != (ZeroMode ? 1 : 0))
			cP5.getController("zero_mode").setValue((ZeroMode ? 1 : 0));

		if ((int) cP5.getController("mem_mode").getValue() != (MemMode ? 1 : 0))
			cP5.getController("mem_mode").setValue((MemMode ? 1 : 0));

		for (int i = 0; i < jog_buttons.length; i++)
			cP5.getController(jog_buttons[i]).setVisible(visible);

		for (int i = 0; i < jog_toggles.length; i++)
			cP5.getController(jog_toggles[i]).setVisible(visible);

		String label;

		// button labels
		label = "   X+";
		if (ZeroMode)
			label = "  GO X0";
		if (MemMode)
			label = "  GO XM";
		if (ArcMode)
			label = ArcCCW ? "  R&UP" : "  R&DN";
		cP5.getController("X+").setLabel(label);

		label = "   X-";
		if (ZeroMode)
			label = " SET X0";
		if (MemMode)
			label = " SET XM";
		if (ArcMode)
			label = ArcCCW ? "  L&DN" : "  L&UP";
		cP5.getController("X-").setLabel(label);

		label = "   Y+";
		if (ZeroMode)
			label = "  GO Y0";
		if (MemMode)
			label = "  GO YM";
		if (ArcMode)
			label = ArcCCW ? "  UP&L" : "  UP&R";
		cP5.getController("Y+").setLabel(label);

		label = "   Y-";
		if (ZeroMode)
			label = " SET Y0";
		if (MemMode)
			label = " SET YM";
		if (ArcMode)
			label = ArcCCW ? "  DN&R" : "  DN&L";
		cP5.getController("Y-").setLabel(label);

		label = "   Z+";
		if (ZeroMode)
			label = "  GO Z0";
		if (MemMode)
			label = "  GO ZM";
		cP5.getController("Z+").setLabel(label);

		label = "   Z-";
		if (ZeroMode)
			label = " SET Z0";
		if (MemMode)
			label = " SET ZM";
		cP5.getController("Z-").setLabel(label);
	}

	public void setup_jog_controls(int x, int y, int y_off) {
		ControlGroup<?> group = cP5.addGroup("GROUP_JOGGING", x, y, 380)
				.activateEvent(true).setLabel("JOGGING").setBarHeight(28)
				.close();
		group.getCaptionLabel().getFont().setSize(14);
		group.getCaptionLabel().getStyle().marginTop = 10;

		Jogging_grp = group;

		x = 0;
		y = y_off;

		cP5.addTextlabel("set_jog_label", "SET JOG ", x, y + 4).setGroup(group);

		Toggle toggle;

		toggle = cP5.addToggle("fractional_jog").setLabel("FRAC")
				.setPosition(x + 50, y).setSize(14, 14).setGroup(group);
		toggle.getCaptionLabel().getStyle().marginTop = -14;
		toggle.getCaptionLabel().getStyle().marginLeft = 18;

		toggle = cP5.addToggle("rapid_positioning").setLabel("RAPID")
				.setPosition(x + 95, y).setSize(14, 14).setGroup(group);
		toggle.getCaptionLabel().getStyle().marginTop = -14;
		toggle.getCaptionLabel().getStyle().marginLeft = 18;

		cP5.addTextlabel("jog_z_label", "Z", x, y + 54).setGroup(group);

		jogZ_ddl = cP5.addDropdownList("JOG Z", x + 15, y + 65, 70, y + 99);
		jogZ_ddl.setGroup(group);
		jogZ_ddl.getCaptionLabel().set("1");
		jogZ_ddl.getCaptionLabel().getStyle().marginTop = 3;
		jogZ_ddl.setBarHeight(14);

		cP5.addTextlabel("jog_y_label", "Y", x, y + 39).setGroup(group);
		jogY_ddl = cP5.addDropdownList("JOG Y", x + 15, y + 50, 70, y + 84);
		jogY_ddl.setGroup(group);
		jogY_ddl.getCaptionLabel().set("1");
		jogY_ddl.getCaptionLabel().getStyle().marginTop = 3;
		jogY_ddl.setBarHeight(14);

		cP5.addTextlabel("jog_x_label", "X", x, y + 24).setGroup(group);
		jogX_ddl = cP5.addDropdownList("JOG X", x + 15, y + 35, 70, y + 69);
		jogX_ddl.setGroup(group);
		jogX_ddl.getCaptionLabel().set("1");
		jogX_ddl.getCaptionLabel().getStyle().marginTop = 3;
		jogX_ddl.setBarHeight(14);

		Numberbox nbr = cP5.addNumberbox("FEED X", 10, x + 95, y + 20, 50, 14);
		nbr.setGroup(group);
		nbr.setLabel("");
		nbr.setMin(1);
		nbr.setMultiplier(1);
		nbr = cP5.addNumberbox("FEED Y", 10, x + 95, y + 35, 50, 14);
		nbr.setGroup(group);
		nbr.setLabel("");
		nbr.setMin(1);
		nbr.setMultiplier(1);
		nbr = cP5.addNumberbox("FEED Z", 10, x + 95, y + 50, 50, 14);
		nbr.setGroup(group);
		nbr.setLabel("");
		nbr.setMin(1);
		nbr.setMultiplier(1);

		int n = FractionalJog ? jog_frac_name.length : jog_dec_name.length;
		for (int i = 0; i < n; i++) {
			jogX_ddl.addItem(FractionalJog ? jog_frac_name[i] : jog_dec_name[i],
					i);
			jogY_ddl.addItem(FractionalJog ? jog_frac_name[i] : jog_dec_name[i],
					i);
			jogZ_ddl.addItem(FractionalJog ? jog_frac_name[i] : jog_dec_name[i],
					i);
		}
		jog_ddl_idx = new int[idx.size()];
		jog_ddl_frac = new boolean[idx.size()];
		for (int i = 0; i < idx.size(); i++) {
			jog_ddl_idx[i] = 4; // index of "1" in jog_dec_value[], default
			jog_ddl_frac[i] = false;
			jog[i] = intCoord(jog_dec_value[jog_ddl_idx[i]]);
		}
	}

	public void update_jog_controls() {
		Jogging_grp.setVisible(PortResponding
				&& (!SendingSequence || SendingSequence && Paused));

		if ((int) cP5.getController("fractional_jog")
				.getValue() != (FractionalJog ? 1 : 0))
			cP5.getController("fractional_jog")
					.setValue((FractionalJog ? 1 : 0));

		if ((int) cP5.getController("rapid_positioning")
				.getValue() != (RapidPositioning ? 1 : 0))
			cP5.getController("rapid_positioning")
					.setValue((RapidPositioning ? 1 : 0));

		if (cP5.getController("FEED X").getValue() != feed[idx.X])
			cP5.getController("FEED X").setValue(feed[idx.X]);

		if (cP5.getController("FEED Y").getValue() != feed[idx.Y])
			cP5.getController("FEED Y").setValue(feed[idx.Y]);

		if (cP5.getController("FEED Z").getValue() != feed[idx.Z])
			cP5.getController("FEED Z").setValue(feed[idx.Z]);

		if (UI_ReloadJogDDL) {
			UI_ReloadJogDDL = false;
			jogX_ddl.clear();
			jogY_ddl.clear();
			jogZ_ddl.clear();
			int n = FractionalJog ? jog_frac_name.length : jog_dec_name.length;
			for (int i = 0; i < n; i++) {
				jogX_ddl.addItem(
						FractionalJog ? jog_frac_name[i] : jog_dec_name[i], i);
				jogY_ddl.addItem(
						FractionalJog ? jog_frac_name[i] : jog_dec_name[i], i);
				jogZ_ddl.addItem(
						FractionalJog ? jog_frac_name[i] : jog_dec_name[i], i);
			}
			// println("jog*_ddl reloaded");
			// println("jogX_ddl.value() = "+jogX_ddl.value());
		}

		jogX_ddl.getCaptionLabel()
				.set(jog_ddl_frac[idx.X]
						? jog_frac_name[jog_ddl_idx[idx.X]]
						: jog_dec_name[jog_ddl_idx[idx.X]]);

		jogY_ddl.getCaptionLabel()
				.set(jog_ddl_frac[idx.Y]
						? jog_frac_name[jog_ddl_idx[idx.Y]]
						: jog_dec_name[jog_ddl_idx[idx.Y]]);

		jogZ_ddl.getCaptionLabel()
				.set(jog_ddl_frac[idx.Z]
						? jog_frac_name[jog_ddl_idx[idx.Z]]
						: jog_dec_name[jog_ddl_idx[idx.Z]]);
	}

	public void setup_homing_controls(int x, int y, int y_off) {
		ControlGroup<?> group = cP5.addGroup("GROUP_HOMING", x, y, 380)
				.activateEvent(true).setLabel("HOMING").setBarHeight(28)
				.close();
		group.getCaptionLabel().getStyle().marginTop = 10;
		group.getCaptionLabel().getFont().setSize(14);

		Homing_grp = group;

		x = 0;
		y = y_off;

		cP5.addTextlabel("homing_limit_label", "LIMITS@ ", 0, y + 4)
				.setGroup(group);

		Numberbox box;

		box = cP5
				.addNumberbox("homing_limit_x", homing_limit[idx.X], x + 10,
						y + 20, 35, 14)
				.setLabel("X").setMultiplier(0.01f).setDecimalPrecision(2)
				.setGroup(group);
		box.getCaptionLabel().getStyle().marginTop = -14;
		box.getCaptionLabel().getStyle().marginLeft = -10;

		box = cP5
				.addNumberbox("homing_limit_y", homing_limit[idx.Y], x + 10,
						y + 35, 35, 14)
				.setLabel("Y").setMultiplier(0.01f).setDecimalPrecision(2)
				.setGroup(group);
		box.getCaptionLabel().getStyle().marginTop = -14;
		box.getCaptionLabel().getStyle().marginLeft = -10;

		box = cP5
				.addNumberbox("homing_limit_z", homing_limit[idx.Z], x + 10,
						y + 50, 35, 14)
				.setLabel("Z").setMultiplier(0.01f).setDecimalPrecision(2)
				.setGroup(group);
		box.getCaptionLabel().getStyle().marginTop = -14;
		box.getCaptionLabel().getStyle().marginLeft = -10;

		box = cP5
				.addNumberbox("homing_infinity", homingInfinity, x + 55, y + 20,
						45, 14)
				.setLabel("INFINITY").setMultiplier(1).setMin(1)
				.setGroup(group);
		box.getCaptionLabel().getStyle().marginTop = 0;
		box.getCaptionLabel().getStyle().marginLeft = 0;

		box = cP5
				.addNumberbox("homing_feed", homingFeed, x + 105, y + 20, 40,
						14)
				.setLabel("FEED").setMultiplier(1).setMin(1).setGroup(group);
		box.getCaptionLabel().getStyle().marginTop = 0;
		box.getCaptionLabel().getStyle().marginLeft = 0;

		Toggle toggle = cP5.addToggle("homing_set_zero").setLabel("SET ZERO")
				.setPosition(x + 55, y).setSize(14, 14).setGroup(group);
		toggle.getCaptionLabel().getStyle().marginTop = -14;
		toggle.getCaptionLabel().getStyle().marginLeft = 18;

		cP5.addButton("HOME XY").setPosition(x + 55, y + 50).setSize(45, 14)
				.setGroup(group);

		cP5.addButton("HOME Z").setPosition(x + 105, y + 50).setSize(40, 14)
				.setGroup(group);
	}

	public void update_homing_controls() {
		Homing_grp.setVisible(PortResponding
				&& (!SendingSequence || SendingSequence && Paused));

		if ((int) cP5.getController("homing_set_zero")
				.getValue() != (HomingSetZero ? 1 : 0))
			cP5.getController("homing_set_zero")
					.setValue((HomingSetZero ? 1 : 0));

		if (cP5.getController("homing_infinity").getValue() != homingInfinity)
			cP5.getController("homing_infinity").setValue(homingInfinity);

		if (cP5.getController("homing_feed").getValue() != homingFeed)
			cP5.getController("homing_feed").setValue(homingFeed);
	}

	public void setup_arc_controls(int x, int y, int y_off) {
		ControlGroup<?> group = cP5.addGroup("GROUP_ARCS", x, y, 380)
				.activateEvent(true).setLabel("ARCS").setBarHeight(28).close();
		group.getCaptionLabel().getStyle().marginTop = 10;
		group.getCaptionLabel().getFont().setSize(14);

		Arcs_grp = group;

		x = 0;
		y = y_off;

		Textfield text;
		text = cP5.addTextfield("ARC_RADIUS", x + 10, y, 50, 20).setLabel("R")
				.setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -10;

		Toggle toggle;

		toggle = cP5.addToggle("ARC_CCW").setPosition(x + 115, y)
				.setSize(30, 20).setGroup(group).setLabel("CCW CW")
				.setMode(ControlP5.SWITCH);
		toggle.getCaptionLabel().getStyle().marginTop = -17;
		toggle.getCaptionLabel().getStyle().marginLeft = -42;

		text = cP5.addTextfield("ARC_START", x + 33, y + 26, 40, 20)
				.setLabel("START@").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -33;

		text = cP5.addTextfield("ARC_END", x + 105, y + 26, 40, 20)
				.setLabel("END@").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -25;

		((Textfield) cP5.getController("ARC_RADIUS"))
				.setText(String.valueOf(arc_radius));
		((Textfield) cP5.getController("ARC_START"))
				.setText(String.valueOf(arc_start));
		((Textfield) cP5.getController("ARC_END"))
				.setText(String.valueOf(arc_end));
	}

	public void update_arc_controls() {
		Arcs_grp.setVisible(PortResponding
				&& (!SendingSequence || SendingSequence && Paused));

		if (UI_ReloadArcTF) {
			((Textfield) cP5.getController("ARC_RADIUS"))
					.setText(String.valueOf(arc_radius));
			((Textfield) cP5.getController("ARC_START"))
					.setText(String.valueOf(arc_start));
			((Textfield) cP5.getController("ARC_END"))
					.setText(String.valueOf(arc_end));
			UI_ReloadArcTF = false;
		}

		if ((int) cP5.getController("ARC_CCW").getValue() != (ArcCCW ? 1 : 0)) {
			cP5.getController("ARC_CCW").setValue((ArcCCW ? 1 : 0));
		}
	}

	public void setup_setting_controls(int x, int y, int y_off) {
		ControlGroup<?> group = cP5.addGroup("GROUP_SETTING", x, y, 380)
				.activateEvent(true).setLabel("SETTING").setBarHeight(28)
				.close();
		group.getCaptionLabel().getStyle().marginTop = 10;
		group.getCaptionLabel().getFont().setSize(14);
		Setting_grp = group;

		x = 0;
		y = y_off;

		int marginLeft = 35;
		int positionLeft = 40;

		Textfield text;

		text = cP5.addTextfield("X_DIR_PIN", x + positionLeft, y, 50, 20)
				.setLabel("X DIR").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		text = cP5.addTextfield("X_STEP_PIN", x + positionLeft, y + 26, 50, 20)
				.setLabel("X STEP").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		text = cP5.addTextfield("X_MIN_PIN", x + positionLeft, y + 52, 50, 20)
				.setLabel("X MIN").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		text = cP5.addTextfield("X_MAX_PIN", x + positionLeft, y + 78, 50, 20)
				.setLabel("X MAX").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		((Textfield) cP5.getController("X_DIR_PIN"))
				.setText(String.valueOf(x_dir_pin));
		((Textfield) cP5.getController("X_STEP_PIN"))
				.setText(String.valueOf(x_step_pin));
		((Textfield) cP5.getController("X_MIN_PIN"))
				.setText(String.valueOf(x_min_pin));
		((Textfield) cP5.getController("X_MAX_PIN"))
				.setText(String.valueOf(x_max_pin));

		positionLeft += 96;

		text = cP5.addTextfield("Y_DIR_PIN", x + positionLeft, y, 50, 20)
				.setLabel("Y DIR").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		text = cP5.addTextfield("Y_STEP_PIN", x + positionLeft, y + 26, 50, 20)
				.setLabel("Y STEP").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		text = cP5.addTextfield("Y_MIN_PIN", x + positionLeft, y + 52, 50, 20)
				.setLabel("Y MIN").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		text = cP5.addTextfield("Y_MAX_PIN", x + positionLeft, y + 78, 50, 20)
				.setLabel("Y MAX").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		((Textfield) cP5.getController("Y_DIR_PIN"))
				.setText(String.valueOf(y_dir_pin));
		((Textfield) cP5.getController("Y_STEP_PIN"))
				.setText(String.valueOf(y_step_pin));
		((Textfield) cP5.getController("Y_MIN_PIN"))
				.setText(String.valueOf(y_min_pin));
		((Textfield) cP5.getController("Y_MAX_PIN"))
				.setText(String.valueOf(y_max_pin));

		positionLeft += 96;

		text = cP5.addTextfield("Z_DIR_PIN", x + positionLeft, y, 50, 20)
				.setLabel("Z DIR").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		text = cP5.addTextfield("Z_STEP_PIN", x + positionLeft, y + 26, 50, 20)
				.setLabel("Z STEP").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		text = cP5.addTextfield("Z_MIN_PIN", x + positionLeft, y + 52, 50, 20)
				.setLabel("Z MIN").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		text = cP5.addTextfield("Z_MAX_PIN", x + positionLeft, y + 78, 50, 20)
				.setLabel("Z MAX").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		((Textfield) cP5.getController("Z_DIR_PIN"))
				.setText(String.valueOf(z_dir_pin));
		((Textfield) cP5.getController("Z_STEP_PIN"))
				.setText(String.valueOf(z_step_pin));
		((Textfield) cP5.getController("Z_MIN_PIN"))
				.setText(String.valueOf(z_min_pin));
		((Textfield) cP5.getController("Z_MAX_PIN"))
				.setText(String.valueOf(z_max_pin));

		positionLeft += 96;

		text = cP5.addTextfield("X_PER_MM", x + positionLeft, y, 50, 20)
				.setLabel("X PPM").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		text = cP5.addTextfield("Y_PER_MM", x + positionLeft, y + 26, 50, 20)
				.setLabel("Y PPM").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		text = cP5.addTextfield("Z_PER_MM", x + positionLeft, y + 52, 50, 20)
				.setLabel("Z PPM").setGroup(group);
		text.getCaptionLabel().getStyle().marginTop = -17;
		text.getCaptionLabel().getStyle().marginLeft = -marginLeft;

		((Textfield) cP5.getController("X_PER_MM"))
				.setText(String.valueOf(x_steps_per_mm));
		((Textfield) cP5.getController("Y_PER_MM"))
				.setText(String.valueOf(y_steps_per_mm));
		((Textfield) cP5.getController("Z_PER_MM"))
				.setText(String.valueOf(z_steps_per_mm));

		Button button;

		button = cP5.addButton("SAVE")
				.setPosition(x + positionLeft - marginLeft, y + 78)
				.setSize(50 + marginLeft, 20).setGroup(group);
		button.getCaptionLabel().getStyle().marginLeft = marginLeft - 6;

		Properties props = System.getProperties();
		String osName = props.getProperty("os.name");
		println(osName);

		String[] settingList;
		if (osName.indexOf("Win") > -1) {
			settingList = loadStrings("setting.ini");
		} else {
			settingList = loadStrings("GRemote.app/Contents/setting.ini");
		}

		try {
			((Textfield) cP5.getController("X_DIR_PIN"))
					.setText(settingList[1]);
			((Textfield) cP5.getController("X_STEP_PIN"))
					.setText(settingList[2]);
			((Textfield) cP5.getController("X_MIN_PIN"))
					.setText(settingList[3]);
			((Textfield) cP5.getController("X_MAX_PIN"))
					.setText(settingList[4]);
			((Textfield) cP5.getController("Y_DIR_PIN"))
					.setText(settingList[5]);
			((Textfield) cP5.getController("Y_STEP_PIN"))
					.setText(settingList[6]);
			((Textfield) cP5.getController("Y_MIN_PIN"))
					.setText(settingList[7]);
			((Textfield) cP5.getController("Y_MAX_PIN"))
					.setText(settingList[8]);
			((Textfield) cP5.getController("Z_DIR_PIN"))
					.setText(settingList[9]);
			((Textfield) cP5.getController("Z_STEP_PIN"))
					.setText(settingList[10]);
			((Textfield) cP5.getController("Z_MIN_PIN"))
					.setText(settingList[11]);
			((Textfield) cP5.getController("Z_MAX_PIN"))
					.setText(settingList[12]);
			((Textfield) cP5.getController("X_PER_MM"))
					.setText(settingList[13]);
			((Textfield) cP5.getController("Y_PER_MM"))
					.setText(settingList[14]);
			((Textfield) cP5.getController("Z_PER_MM"))
					.setText(settingList[15]);
		} catch (Exception e) {
			((Textfield) cP5.getController("X_DIR_PIN"))
					.setText(String.valueOf(x_dir_pin));
			((Textfield) cP5.getController("X_STEP_PIN"))
					.setText(String.valueOf(x_step_pin));
			((Textfield) cP5.getController("X_MIN_PIN"))
					.setText(String.valueOf(x_min_pin));
			((Textfield) cP5.getController("X_MAX_PIN"))
					.setText(String.valueOf(x_max_pin));
			((Textfield) cP5.getController("Y_DIR_PIN"))
					.setText(String.valueOf(y_dir_pin));
			((Textfield) cP5.getController("Y_STEP_PIN"))
					.setText(String.valueOf(y_step_pin));
			((Textfield) cP5.getController("Y_MIN_PIN"))
					.setText(String.valueOf(y_min_pin));
			((Textfield) cP5.getController("Y_MAX_PIN"))
					.setText(String.valueOf(y_max_pin));
			((Textfield) cP5.getController("Z_DIR_PIN"))
					.setText(String.valueOf(z_dir_pin));
			((Textfield) cP5.getController("Z_STEP_PIN"))
					.setText(String.valueOf(z_step_pin));
			((Textfield) cP5.getController("Z_MIN_PIN"))
					.setText(String.valueOf(z_min_pin));
			((Textfield) cP5.getController("Z_MAX_PIN"))
					.setText(String.valueOf(z_max_pin));
			((Textfield) cP5.getController("X_PER_MM"))
					.setText(String.valueOf(x_steps_per_mm));
			((Textfield) cP5.getController("Y_PER_MM"))
					.setText(String.valueOf(y_steps_per_mm));
			((Textfield) cP5.getController("Z_PER_MM"))
					.setText(String.valueOf(z_steps_per_mm));
		}
	}

	public void update_setting_controls() {
		Setting_grp.setVisible(PortResponding
				&& (!SendingSequence || SendingSequence && Paused));
	}

	public void update_textfields() {
		if (UI_ClearFocusTF) {
			((Textfield) cP5.getController("GCODE")).setFocus(false);
			((Textfield) cP5.getController("ARC_RADIUS")).setFocus(false);
			((Textfield) cP5.getController("ARC_START")).setFocus(false);
			((Textfield) cP5.getController("ARC_END")).setFocus(false);
			UI_ClearFocusTF = false;
		}
	}

	public void open_group(char g) {
		if (Jogging_grp == null) {
			return;
		}

		if (Homing_grp == null) {
			return;
		}

		if (Arcs_grp == null) {
			return;
		}

		if (Setting_grp == null) {
			return;
		}

		if (g == 'J') {
			Jogging_grp.open();
			Homing_grp.close();
			Arcs_grp.close();
			Setting_grp.close();
		} else {
			Jogging_grp.close();
		}

		if (g == 'H') {
			Homing_grp.open();
			Jogging_grp.close();
			Arcs_grp.close();
			Setting_grp.close();
		} else {
			Homing_grp.close();
		}

		if (g == 'A') {
			Arcs_grp.open();
			Homing_grp.close();
			Jogging_grp.close();
			Setting_grp.open();
		} else {
			Arcs_grp.close();
		}

		if (g == 'S') {
			Setting_grp.open();
			Homing_grp.close();
			Jogging_grp.close();
			Arcs_grp.close();
		} else {
			Setting_grp.close();
		}
	}

	public void update_groups() {
		Jogging_grp
				.setColorLabel(Jogging_grp.isOpen() ? 0xFFFFFFFF : 0xFF888888);

		Homing_grp.setColorLabel(Homing_grp.isOpen() ? 0xFFFFFFFF : 0xFF888888);

		Arcs_grp.setColorLabel(Arcs_grp.isOpen() ? 0xFFFFFFFF : 0xFF888888);

		Setting_grp
				.setColorLabel(Setting_grp.isOpen() ? 0xFFFFFFFF : 0xFF888888);
	}

	// Conversion, helpers etc

	class EnumXYZ {
		public String strVal[] = {"X", "Y", "Z"};

		public int X = 0;
		public int Y = 1;
		public int Z = 2;

		public int size() {
			return 3;
		}

		public int toAZ(int idx) {
			return idx + 23;
		}
	}

	class EnumAZ {
		public String strVal[] = {"A", "B", "C", "D", "E", "F", "G", "H", "I",
				"J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V",
				"W", "X", "Y", "Z"};
		public int A = 0;
		public int B = 1;
		public int C = 2;
		public int D = 3;
		public int E = 4;
		public int F = 5;
		public int G = 6;
		public int H = 7;
		public int I = 8;
		public int J = 9;
		public int K = 10;
		public int L = 11;
		public int M = 12;
		public int N = 13;
		public int O = 14;
		public int P = 15;
		public int Q = 16;
		public int R = 17;
		public int S = 18;
		public int T = 19;
		public int U = 20;
		public int V = 21;
		public int W = 22;
		public int X = 23;
		public int Y = 24;
		public int Z = 25;

		public int size() {
			return 26;
		}

		public int toXYZ(int idx) {
			return idx - 23;
		}
	}

	public boolean isFloat(String input) {
		try {
			Float.parseFloat(input);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public float[] polar2cartesian(float r, float t) {
		float[] xy = {0.0f, 0.0f};
		xy[idx.X] = (float) (r * Math.cos(Math.toRadians(t)));
		xy[idx.Y] = (float) (r * Math.sin(Math.toRadians(t)));
		return xy;
	}

	public String floatCoord(int coord) {
		return String.valueOf((float) coord / resolution);
	}

	public int intCoord(float coord) {
		return (int) (coord * resolution);
	}

	public int[] intCoord(float[] f) {
		int[] I = new int[f.length];
		for (int i = 0; i < f.length; i++)
			I[i] = intCoord(f[i]);
		return I;
	}

	/**
	 * Single entry point.
	 * 
	 * @param passedArgs
	 *            Command line arguments.
	 */
	static public void main(String[] passedArgs) {
		// String[] appletArgs = new String[]{"GRemote"};
		//
		// if (passedArgs != null) {
		// PApplet.main(concat(appletArgs, passedArgs));
		// } else {
		// PApplet.main(appletArgs);
		// }
	}
}
