package com.chameleonvision.vision.pipeline;

import com.chameleonvision.vision.enums.ImageFlipMode;

@SuppressWarnings("ALL")
public class CVPipelineSettings {
    public ImageFlipMode flipMode = ImageFlipMode.NONE;
    public String nickname = "New Pipeline";
    public double exposure = 50.0;
    public double brightness = 50.0;
}