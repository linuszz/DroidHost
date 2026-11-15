#pragma once
#include <stdint.h>

#define DROIDHOST_PRESENT_SYNC_NAME "present-sync"
#define DROIDHOST_PRESENT_SYNC_SIZE 4096

struct droiddeck_present_sync {
    uint32_t issued;
    uint32_t done;
};
