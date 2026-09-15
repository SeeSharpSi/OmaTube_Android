package dev.omatube.app.connectivity

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConnectivityClassifierTest {

    @Test
    fun noActiveNetworkIsConservativeData() {
        assertThat(
            classifyConnection(
                hasActiveNetwork = false,
                hasWifi = false,
                hasCellular = false,
                isMetered = false,
            ),
        ).isEqualTo(ConnectionType.DATA)
    }

    @Test
    fun wifiTransportMapsToWifi() {
        assertThat(
            classifyConnection(
                hasActiveNetwork = true,
                hasWifi = true,
                hasCellular = false,
                isMetered = false,
            ),
        ).isEqualTo(ConnectionType.WIFI)
    }

    @Test
    fun cellularTransportMapsToData() {
        assertThat(
            classifyConnection(
                hasActiveNetwork = true,
                hasWifi = false,
                hasCellular = true,
                isMetered = true,
            ),
        ).isEqualTo(ConnectionType.DATA)
    }

    @Test
    fun otherTransportsUseMeteredFallback() {
        assertThat(
            classifyConnection(
                hasActiveNetwork = true,
                hasWifi = false,
                hasCellular = false,
                isMetered = true,
            ),
        ).isEqualTo(ConnectionType.DATA)
        assertThat(
            classifyConnection(
                hasActiveNetwork = true,
                hasWifi = false,
                hasCellular = false,
                isMetered = false,
            ),
        ).isEqualTo(ConnectionType.WIFI)
    }

    @Test
    fun wifiWinsOverCellularWhenBothPresent() {
        assertThat(
            classifyConnection(
                hasActiveNetwork = true,
                hasWifi = true,
                hasCellular = true,
                isMetered = true,
            ),
        ).isEqualTo(ConnectionType.WIFI)
    }
}
