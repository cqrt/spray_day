# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# Room, Compose and MapLibre all ship consumer ProGuard rules, so nothing is
# required for them here. Add keeps below as the data layer grows.

# Keep the GPX/route data classes once they are added, so future
# serialisation stays intact under R8.
