"""Settings, debugger-negative and optional APK checks over the live-status driver.

Requires the same prebuilt release JARs, full JDK 21 and GUI host classes as that driver.
Only copied fixtures, isolated preferences and child JVMs are owned by this run.
"""
from gui_live_status import main


if __name__ == '__main__':
    main(acceptance=True)
