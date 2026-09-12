package com.chk.binancebybit

import java.net.ServerSocket
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BybitRecoveryTest {
    private class MockBybit(private val handler: (String,String,String) -> Pair<Int,String>): AutoCloseable {
        private val socket=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        private val failure=AtomicReference<Throwable?>()
        val url="http://127.0.0.1:${socket.localPort}"
        private val worker=thread(isDaemon=true) {
            try { while(!socket.isClosed) socket.accept().use { client ->
                client.soTimeout=5000
                val input=client.getInputStream().bufferedReader()
                val request=input.readLine().split(" ")
                var length=0
                while(true) {
                    val header=input.readLine() ?: break
                    if(header.isEmpty())break
                    if(header.startsWith("Content-Length:",ignoreCase=true))length=header.substringAfter(':').trim().toInt()
                }
                val body=CharArray(length);var received=0
                while(received<length) {
                    val n=input.read(body,received,length-received)
                    check(n>0);received+=n
                }
                val (code,response)=handler(request[0],request[1].substringBefore('?'),String(body))
                val bytes=response.toByteArray(Charsets.UTF_8)
                client.getOutputStream().apply {
                    write("HTTP/1.1 $code Response\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes);flush()
                }
            } } catch(t:Throwable) { if(!socket.isClosed)failure.set(t) }
        }
        override fun close() { socket.close();worker.join(6000);failure.get()?.let { throw AssertionError("Mock Bybit request failed",it) } }
    }
    private val proposal = TradeProposal("00000000-0000-0000-0000-000000000001","RENDERUSDC","SELL","LIMIT",7.35,5.0,1.47,
        "test",null,"chatgpt-test","processing",Instant.now().plusSeconds(3600).toString(),Instant.now().toString())
    private val link = "chk-0000000000000000000000000000"
    private fun row() = """{"orderId":"confirmed-id","orderLinkId":"$link","symbol":"RENDERUSDC","side":"Sell","orderStatus":"New","qty":"5","price":"1.47"}"""
    @Test fun existingOrderIsRecoveredBeforePreflightAndNeverSubmittedAgain() {
        val posts=AtomicInteger();val reserved=AtomicInteger()
        MockBybit { method,path,_ ->
            if(method=="POST")posts.incrementAndGet()
            val body=if(path.endsWith("/time")) """{"retCode":0,"time":${System.currentTimeMillis()}}"""
                else """{"retCode":0,"result":{"list":[${row()}]}}"""
            200 to body
        }.use { server ->
            val client=BybitTradeClient("test-key","test-secret",server.url)
            repeat(2){assertEquals("confirmed-id",client.execute(proposal){reserved.incrementAndGet()}.orderId)}
            assertEquals(0,posts.get());assertEquals(0,reserved.get())
        }
    }
    @Test fun post500AfterAcceptanceRecoversWithoutASecondPost() {
        val posts=AtomicInteger();val reserved=AtomicInteger()
        MockBybit { _,path,requestBody ->
            var code=200
            val body=when(path){
                "/v5/market/time" -> """{"retCode":0,"time":${System.currentTimeMillis()}}"""
                "/v5/order/realtime", "/v5/order/history" -> """{"retCode":0,"result":{"list":[${if(posts.get()>0)row() else ""}]}}"""
                "/v5/user/query-api" -> """{"retCode":0,"result":{"readOnly":0,"permissions":{"Spot":["SpotTrade"]}}}"""
                "/v5/market/instruments-info" -> """{"retCode":0,"result":{"list":[{"status":"Trading","lotSizeFilter":{"basePrecision":"0.01"},"priceFilter":{"tickSize":"0.0001"}}]}}"""
                "/v5/account/wallet-balance" -> """{"retCode":0,"result":{"list":[{"coin":[{"coin":"RENDER","walletBalance":"100","availableToWithdraw":"100","locked":"0"}]}]}}"""
                "/v5/order/create" -> {
                    assertEquals(link,JSONObject(requestBody).getString("orderLinkId"))
                    posts.incrementAndGet();code=500;"""{"retCode":10000,"retMsg":"lost response"}"""
                }
                else -> { code=404; "{}" }
            }
            code to body
        }.use { server ->
            val client=BybitTradeClient("test-key","test-secret",server.url)
            val result=client.execute(proposal){reserved.incrementAndGet()}
            assertEquals("confirmed-id",result.orderId);assertEquals("New",result.orderStatus)
            assertEquals(1,posts.get());assertEquals(1,reserved.get())
        }
    }
}
