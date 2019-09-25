package com.chameleonvision.vision.process;

import com.chameleonvision.settings.SettingsManager;
import com.chameleonvision.vision.Pipeline;
import com.chameleonvision.vision.camera.Camera;
import com.chameleonvision.web.Server;
import com.chameleonvision.web.ServerHandler;
import edu.wpi.cscore.VideoException;
import edu.wpi.first.networktables.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class VisionProcess implements Runnable {

	private final Camera camera;
	private final String cameraName;
	private final CameraProcess cameraProcess;
	// NetworkTables
	private NetworkTableEntry ntPipelineEntry;
	private NetworkTableEntry ntDriverModeEntry;
	private NetworkTableEntry ntYawEntry;
	private NetworkTableEntry ntPitchEntry;
	private NetworkTableEntry ntDistanceEntry;
	private NetworkTableEntry ntTimeStampEntry;
	private NetworkTableEntry ntValidEntry;
	// chameleon specific
	private Pipeline currentPipeline;
	private CVProcess cvProcess;
	// pipeline process items
	private List<MatOfPoint> FoundContours = new ArrayList<>();
	private List<MatOfPoint> FilteredContours = new ArrayList<>();
	private List<RotatedRect> GroupedContours = new ArrayList<>();
	private Mat cameraInputMat = new Mat();
	private Mat hsvThreshMat = new Mat();
	private Mat streamOutputMat = new Mat();
	private Scalar contourRectColor = new Scalar(255, 0, 0);
	private long TimeStamp = 0;

	public VisionProcess(Camera processCam) {
		camera = processCam;
		this.cameraName = camera.name;

		// NetworkTables
		NetworkTable ntTable = NetworkTableInstance.getDefault().getTable("/chameleon-vision/" + cameraName);
		ntPipelineEntry = ntTable.getEntry("Pipeline");
		ntDriverModeEntry = ntTable.getEntry("Driver_Mode");
		ntPitchEntry = ntTable.getEntry("Pitch");
		ntYawEntry = ntTable.getEntry("Yaw");
		ntDistanceEntry = ntTable.getEntry("Distance");
		ntTimeStampEntry = ntTable.getEntry("TimeStamp");
		ntValidEntry = ntTable.getEntry("Valid");
		ntDriverModeEntry.addListener(this::DriverModeListener, EntryListenerFlags.kUpdate);
		ntPipelineEntry.addListener(this::PipelineListener, EntryListenerFlags.kUpdate);
		ntDriverModeEntry.setBoolean(false);
		ntPipelineEntry.setString("pipeline" + camera.getCurrentPipelineIndex());

		// camera settings
		cvProcess = new CVProcess(camera.getCamVals());
		cameraProcess = new CameraProcess(camera);
	}

	private void DriverModeListener(EntryNotification entryNotification) {
		if (entryNotification.value.getBoolean()) {
			camera.setExposure(25);
			camera.setBrightness(15);
		} else {
			Pipeline pipeline = camera.getCurrentPipeline();
			camera.setExposure(pipeline.exposure);
			camera.setBrightness(pipeline.brightness);
		}
	}

	private void PipelineListener(EntryNotification entryNotification) {
		var ntPipelineIndex = Integer.parseInt(entryNotification.value.getString().replace("pipeline", ""));
		if (camera.getPipelines().containsKey(ntPipelineIndex)) {
//            camera.setEntryNotification.value.getString());
			var pipeline = camera.getCurrentPipeline();
			camera.setCurrentPipelineIndex(ntPipelineIndex);
			try{
				camera.setExposure(pipeline.exposure);
			}
			catch (VideoException e){
				System.err.println(e.toString());
			}
			camera.setBrightness(pipeline.brightness);
			HashMap<String, Object> pipeChange = new HashMap<>();
			pipeChange.put("curr_pipeline", ntPipelineIndex);
			ServerHandler.broadcastMessage(pipeChange);
			ServerHandler.sendFullSettings();

		} else {
			ntPipelineEntry.setString("pipeline" + camera.getCurrentPipelineIndex());
		}
	}

	private void drawContour(Mat inputMat, RotatedRect contourRect) {
		if (contourRect == null) return;
		List<MatOfPoint> drawnContour = new ArrayList<>();
		Point[] vertices = new Point[4];
		contourRect.points(vertices);
		drawnContour.add(new MatOfPoint(vertices));
		Imgproc.drawContours(inputMat, drawnContour, 0, contourRectColor, 3);
		Imgproc.circle(inputMat, contourRect.center, 3, contourRectColor);
	}

	private void updateNetworkTables(PipelineResult pipelineResult) {
		ntValidEntry.setBoolean(pipelineResult.IsValid);
		if (pipelineResult.IsValid) {
			ntYawEntry.setNumber(pipelineResult.Yaw);
			ntPitchEntry.setNumber(pipelineResult.Pitch);
		}
		ntTimeStampEntry.setNumber(TimeStamp);
	}

	private PipelineResult runVisionProcess(Mat inputImage, Mat outputImage) {
		var pipelineResult = new PipelineResult();

		if (currentPipeline == null) {
			return pipelineResult;
		}
		if (!currentPipeline.orientation.equals("Normal")) {
			Core.flip(inputImage, inputImage, -1);
		}
		if (ntDriverModeEntry.getBoolean(false)) {
			inputImage.copyTo(outputImage);
			return pipelineResult;
		}
		Scalar hsvLower = new Scalar(currentPipeline.hue.get(0), currentPipeline.saturation.get(0), currentPipeline.value.get(0));
		Scalar hsvUpper = new Scalar(currentPipeline.hue.get(1), currentPipeline.saturation.get(1), currentPipeline.value.get(1));

		cvProcess.HSVThreshold(inputImage, hsvThreshMat, hsvLower, hsvUpper, currentPipeline.erode, currentPipeline.dilate);

		if (currentPipeline.is_binary == 1) {
			Imgproc.cvtColor(hsvThreshMat, outputImage, Imgproc.COLOR_GRAY2BGR, 3);
		} else {
			inputImage.copyTo(outputImage);
		}
		FoundContours = cvProcess.FindContours(hsvThreshMat);
		if (FoundContours.size() > 0) {
			FilteredContours = cvProcess.FilterContours(FoundContours, currentPipeline.area, currentPipeline.ratio, currentPipeline.extent);
			if (FilteredContours.size() > 0) {
				GroupedContours = cvProcess.GroupTargets(FilteredContours, currentPipeline.target_intersection, currentPipeline.target_group);
				if (GroupedContours.size() > 0) {
					var finalRect = cvProcess.SortTargetsToOne(GroupedContours, currentPipeline.sort_mode);
//					System.out.printf("Largest Contour Area: %.2f\n", finalRect.size.area());
					pipelineResult.RawPoint = finalRect;
					pipelineResult.IsValid = true;
					if (!currentPipeline.is_calibrated) {
						pipelineResult.CalibratedX = camera.getCamVals().CenterX;
						pipelineResult.CalibratedY = camera.getCamVals().CenterY;
					} else {
						pipelineResult.CalibratedX = (finalRect.center.y - currentPipeline.B) / currentPipeline.M;
						pipelineResult.CalibratedY = finalRect.center.x * currentPipeline.M + currentPipeline.B;
					}
					pipelineResult.Pitch = camera.getCamVals().CalculatePitch(finalRect.center.y, pipelineResult.CalibratedY);
					pipelineResult.Yaw = camera.getCamVals().CalculateYaw(finalRect.center.x, pipelineResult.CalibratedX);
					drawContour(outputImage, finalRect);
				}
			}
		}

		return pipelineResult;
	}

	@Override
	public void run() {
		// processing time tracking
		long startTime;
		long fpsLastTime = 0;
		double processTimeMs;
		double fps = 0;
		double uiFps = 0;
		int maxFps = camera.getVideoMode().fps;

		new Thread(cameraProcess).start();

		long lastFrameEndNanosec = 0;

		while (!Thread.interrupted()) {
			startTime = System.nanoTime();
			if ((startTime - lastFrameEndNanosec) * 1e-6 >= 1000.0 / maxFps + 3) { // 3 additional fps to allow for overhead
				FoundContours.clear();
				FilteredContours.clear();
				GroupedContours.clear();

				// update FPS for ui only every 0.5 seconds
				if ((startTime - fpsLastTime) * 1e-6 >= 500) {
					if (fps >= maxFps) {
						uiFps = maxFps;
					} else {
						uiFps = fps;
					}
					fpsLastTime = System.nanoTime();
				}

				currentPipeline = camera.getCurrentPipeline();
				// start fps counter right before grabbing input frame
				TimeStamp = cameraProcess.getLatestFrame(cameraInputMat);
				if (cameraInputMat.cols() == 0 && cameraInputMat.rows() == 0) {
					continue;
				}

				// get vision data
				var pipelineResult = runVisionProcess(cameraInputMat, streamOutputMat);
				updateNetworkTables(pipelineResult);
				if (cameraName.equals(SettingsManager.GeneralSettings.curr_camera)) {
					HashMap<String, Object> WebSend = new HashMap<>();
					HashMap<String, Object> point = new HashMap<>();
					List<Double> center = new ArrayList<>();
					if (pipelineResult.IsValid) {
						center.add(pipelineResult.RawPoint.center.x);
						center.add(pipelineResult.RawPoint.center.y);
						point.put("pitch", pipelineResult.Pitch);
						point.put("yaw", pipelineResult.Yaw);
					} else {
						center.add(0.0);
						center.add(0.0);
						point.put("pitch", 0);
						point.put("yaw", 0);
					}
					point.put("fps", uiFps);
					WebSend.put("point", point);
					WebSend.put("raw_point", center);
					ServerHandler.broadcastMessage(WebSend);
				}

				cameraProcess.updateFrame(streamOutputMat);

				cameraInputMat.release();
				hsvThreshMat.release();

				// calculate FPS
				lastFrameEndNanosec = System.nanoTime();
				processTimeMs = (lastFrameEndNanosec - startTime) * 1e-6;
				fps = 1000 / processTimeMs;
				//please dont enable if you are not debugging
				//				System.out.printf("%s - Process time: %-5.2fms, FPS: %-5.2f, FoundContours: %d, FilteredContours: %d, GroupedContours: %d\n", cameraName, processTimeMs, fps, FoundContours.size(), FilteredContours.size(), GroupedContours.size());
			}
		}
	}
}