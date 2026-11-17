package com.shilapi.xcertplay.geely

/** Main-thread, one callback per frame; always applies the newest valid surface/generation. */
internal class ReceiverSurfaceWork(
    private val post:(Runnable)->Unit,
    private val remove:(Runnable)->Unit,
    private val valid:(Int,Any?)->Boolean,
    private val apply:(String?)->Unit,
) {
    private data class Request(val generation:Int,val surface:Any?,val restore:String?)
    private var latest:Request?=null
    private var queued=false
    private val frame=Runnable {
        val request=latest;latest=null;queued=false
        if(request!=null && valid(request.generation,request.surface)) apply(request.restore)
    }
    fun request(generation:Int,surface:Any?,restore:String?=null) {
        if(!valid(generation,surface)) return
        val previous=latest?.takeIf {it.generation==generation && it.surface===surface}
        latest=Request(generation,surface,restore ?: previous?.restore)
        if(!queued) {queued=true;post(frame)}
    }
    fun cancel() {remove(frame);queued=false;latest=null}
}
