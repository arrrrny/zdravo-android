package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages message fragmentation and reassembly
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class FragmentManager {
    
    companion object {
        private const val TAG = "FragmentManager"
        private const val MAX_FRAGMENT_SIZE = 500
        private const val FRAGMENT_TIMEOUT = 30000L // 30 seconds
        private const val CLEANUP_INTERVAL = 10000L // 10 seconds
    }
    
    // Fragment storage
    private val incomingFragments = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()
    private val fragmentMetadata = ConcurrentHashMap<String, Triple<UByte, Int, Long>>() // originalType, totalFragments, timestamp
    
    // Delegate for callbacks
    var delegate: FragmentManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Create fragments from a large packet
     */
    fun createFragments(packet: BitchatPacket): List<BitchatPacket> {
        val data = packet.toBinaryData() ?: return emptyList()
        
        if (data.size <= MAX_FRAGMENT_SIZE) {
            return listOf(packet) // No fragmentation needed
        }
        
        val fragments = mutableListOf<BitchatPacket>()
        val fragmentID = generateFragmentID()
        
        // Calculate header size (13 bytes for fragment metadata)
        val headerSize = 13
        val dataPerFragment = MAX_FRAGMENT_SIZE - headerSize
        val totalFragments = (data.size + dataPerFragment - 1) / dataPerFragment
        
        Log.d(TAG, "Creating ${totalFragments} fragments for ${data.size} byte packet")
        
        for (i in 0 until totalFragments) {
            val start = i * dataPerFragment
            val end = minOf(start + dataPerFragment, data.size)
            val fragmentData = data.sliceArray(start until end)
            
            val fragmentPayload = createFragmentPayload(
                fragmentID = fragmentID,
                index = i,
                total = totalFragments,
                originalType = packet.type,
                data = fragmentData
            )
            
            val fragmentType = when (i) {
                0 -> MessageType.FRAGMENT_START
                totalFragments - 1 -> MessageType.FRAGMENT_END
                else -> MessageType.FRAGMENT_CONTINUE
            }
            
            val fragmentPacket = BitchatPacket(
                type = fragmentType.value,
                ttl = packet.ttl,
                senderID = packet.senderID,
                recipientID = packet.recipientID,
                timestamp = packet.timestamp,
                payload = fragmentPayload,
                signature = null // Fragments aren't individually signed
            )
            
            fragments.add(fragmentPacket)
        }
        
        return fragments
    }
    
    /**
     * Handle incoming fragment
     */
    fun handleFragment(packet: BitchatPacket): BitchatPacket? {
        if (packet.payload.size < 13) {
            Log.w(TAG, "Fragment packet too small: ${packet.payload.size}")
            return null
        }
        
        try {
            // Extract fragment metadata (same format as iOS)
            val fragmentIDData = packet.payload.sliceArray(0..7)
            val fragmentID = fragmentIDData.contentHashCode().toString()
            
            val index = ((packet.payload[8].toInt() and 0xFF) shl 8) or (packet.payload[9].toInt() and 0xFF)
            val total = ((packet.payload[10].toInt() and 0xFF) shl 8) or (packet.payload[11].toInt() and 0xFF)
            val originalType = packet.payload[12].toUByte()
            val fragmentData = packet.payload.sliceArray(13 until packet.payload.size)
            
            Log.d(TAG, "Received fragment $index/$total for fragmentID: $fragmentID, originalType: $originalType")
            
            // Store fragment
            if (!incomingFragments.containsKey(fragmentID)) {
                incomingFragments[fragmentID] = mutableMapOf()
                fragmentMetadata[fragmentID] = Triple(originalType, total, System.currentTimeMillis())
            }
            
            incomingFragments[fragmentID]?.put(index, fragmentData)
            
            // Check if we have all fragments
            if (incomingFragments[fragmentID]?.size == total) {
                Log.d(TAG, "All fragments received for $fragmentID, reassembling...")
                
                // Reassemble message
                val reassembledData = mutableListOf<Byte>()
                for (i in 0 until total) {
                    incomingFragments[fragmentID]?.get(i)?.let { data ->
                        reassembledData.addAll(data.asIterable())
                    }
                }
                
                // Parse and return reassembled packet
                val reassembledPacket = BitchatPacket.fromBinaryData(reassembledData.toByteArray())
                
                // Cleanup
                incomingFragments.remove(fragmentID)
                fragmentMetadata.remove(fragmentID)
                
                if (reassembledPacket != null) {
                    Log.d(TAG, "Successfully reassembled packet of ${reassembledData.size} bytes")
                    return reassembledPacket
                } else {
                    Log.e(TAG, "Failed to parse reassembled packet")
                }
            } else {
                val received = incomingFragments[fragmentID]?.size ?: 0
                Log.d(TAG, "Fragment $index stored, have $received/$total fragments for $fragmentID")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle fragment: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Create fragment payload with metadata
     */
    private fun createFragmentPayload(
        fragmentID: String,
        index: Int,
        total: Int,
        originalType: UByte,
        data: ByteArray
    ): ByteArray {
        val payload = ByteArray(13 + data.size)
        
        // Fragment ID (8 bytes)
        val idBytes = fragmentID.toByteArray()
        System.arraycopy(idBytes, 0, payload, 0, minOf(8, idBytes.size))
        
        // Index (2 bytes, big-endian)
        payload[8] = ((index shr 8) and 0xFF).toByte()
        payload[9] = (index and 0xFF).toByte()
        
        // Total (2 bytes, big-endian)
        payload[10] = ((total shr 8) and 0xFF).toByte()
        payload[11] = (total and 0xFF).toByte()
        
        // Original type (1 byte)
        payload[12] = originalType.toByte()
        
        // Fragment data
        System.arraycopy(data, 0, payload, 13, data.size)
        
        return payload
    }
    
    /**
     * Generate unique fragment ID
     */
    private fun generateFragmentID(): String {
        return "${System.currentTimeMillis()}-${kotlin.random.Random.nextInt()}"
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Fragment Manager Debug Info ===")
            appendLine("Active Fragment Sets: ${incomingFragments.size}")
            fragmentMetadata.forEach { (fragmentID, metadata) ->
                val (originalType, totalFragments, timestamp) = metadata
                val received = incomingFragments[fragmentID]?.size ?: 0
                val ageSeconds = (System.currentTimeMillis() - timestamp) / 1000
                appendLine("  - $fragmentID: $received/$totalFragments fragments, type: $originalType, age: ${ageSeconds}s")
            }
        }
    }
    
    /**
     * Start periodic cleanup of old fragments
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldFragments()
            }
        }
    }
    
    /**
     * Clean up old fragments (older than 30 seconds)
     */
    private fun cleanupOldFragments() {
        val cutoffTime = System.currentTimeMillis() - FRAGMENT_TIMEOUT
        val fragmentsToRemove = mutableListOf<String>()
        
        fragmentMetadata.entries.forEach { (fragmentID, metadata) ->
            if (metadata.third < cutoffTime) {
                fragmentsToRemove.add(fragmentID)
            }
        }
        
        fragmentsToRemove.forEach { fragmentID ->
            incomingFragments.remove(fragmentID)
            fragmentMetadata.remove(fragmentID)
        }
        
        if (fragmentsToRemove.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${fragmentsToRemove.size} old fragment sets")
        }
    }
    
    /**
     * Clear all fragments
     */
    fun clearAllFragments() {
        incomingFragments.clear()
        fragmentMetadata.clear()
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllFragments()
    }
}

/**
 * Delegate interface for fragment manager callbacks
 */
interface FragmentManagerDelegate {
    fun onPacketReassembled(packet: BitchatPacket)
}
