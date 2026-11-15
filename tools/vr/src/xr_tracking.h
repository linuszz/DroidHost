#pragma once
#include <stdint.h>

#define DROIDHOST_XR_MAGIC 0x52584444u
#define DROIDHOST_XR_VERSION 1u
#define DROIDHOST_XR_FILE "droiddeck-xr-tracking"
#define DROIDHOST_XR_STICK_CLICK (1u << 0)
#define DROIDHOST_XR_PRIMARY_CLICK (1u << 1)
#define DROIDHOST_XR_SECONDARY_CLICK (1u << 2)
#define DROIDHOST_XR_MENU_CLICK (1u << 3)
#define DROIDHOST_XR_TRIGGER_TOUCH (1u << 4)
#define DROIDHOST_XR_STICK_TOUCH (1u << 5)
#define DROIDHOST_XR_PRIMARY_TOUCH (1u << 6)
#define DROIDHOST_XR_SECONDARY_TOUCH (1u << 7)
#define DROIDHOST_XR_THUMBREST_TOUCH (1u << 8)

struct DroidHostXrPose {
    float orientation[4];
    float position[3];
    uint32_t flags;
};

struct DroidHostXrEye {
    DroidHostXrPose pose;
    float fov[4];
    uint32_t width, height;
};

struct DroidHostXrHand {
    DroidHostXrPose pose;
    float trigger, squeeze, stick[2];
    uint32_t buttons, active;
};

struct DroidHostXrTracking {
    uint32_t magic, version, seq, active;
    int64_t sampled_ns, predicted_ns, period_ns;
    DroidHostXrPose head;
    DroidHostXrEye eyes[2];
    DroidHostXrHand hands[2];
};

static_assert(sizeof(DroidHostXrTracking) == 296, "XR tracking IPC layout");

#define DROIDHOST_XR_HAPTIC_MAGIC 0x48584444u
#define DROIDHOST_XR_HAPTIC_FILE "droiddeck-xr-haptics"

struct DroidHostXrPulse {
    uint64_t id;
    int64_t duration_ns;
    float amplitude, frequency;
};

struct DroidHostXrHaptics {
    uint32_t magic, version, seq, reserved;
    DroidHostXrPulse hands[2];
};

static_assert(sizeof(DroidHostXrHaptics) == 64, "XR haptics IPC layout");
