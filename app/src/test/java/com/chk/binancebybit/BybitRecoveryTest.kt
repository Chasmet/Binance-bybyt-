package com.chk.binancebybit

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BybitRecoveryTest {
    private val proposal = TradeProposal("00000000-0000-0000-0000-000000000001","RENDERUSDC","SELL","LIMIT",7.35,5.0,1.47,
        "test",null,"chatgpt-test","processing",Instant.now().plusSeconds(3600).toString(),Instant.now().toString())
    private val link = "chk-0000000000000000000000000000"
    private fun row() = """{"orderId":"confirmed-id","orderLinkId":"$link","symbol":"RENDERUSDC","side":"Sell","orderStatus":"New","qty":"5","price":"1.47"}"""
    @Test fun existingOrderIsRecoveredBeforePreflightAndNeverSubmittedAgain() {
        val posts=AtomicInteger();val reserved=AtomicInteger()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { x ->
            if(x.requestMethod=="POST")posts.incrementAndGet()
            val body=if(x.requestURI.path.endsWith("/time")) """{"retCode":0,"time":${System.currentTimeMillis()}}"""
                else """{"retCode":0,"result":{"list":[${row()}]}}"""
            val bytes=body.toByteArray();x.sendResponseHeaders(200,bytes.size.toLong());x.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client=BybitTradeClient("test-key","test-secret","http://127.0.0.1:${server.address.port}")
            repeat(2){assertEquals("confirmed-id",client.execute(proposal){reserved.incrementAndGet()}.orderId)}
            assertEquals(0,posts.get());assertEquals(0,reserved.get())
        } finally { server.stop(0) }
    }
    @Test fun post500AfterAcceptanceRecoversWithoutASecondPost() {
        val posts=AtomicInteger();val reserved=AtomicInteger()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { x ->
            var code=200
            val body=when(x.requestURI.path){
                "/v5/market/time" -> """{"retCode":0,"time":${System.currentTimeMillis()}}"""
                "/v5/order/realtime", "/v5/order/history" -> """{"retCode":0,"result":{"list":[${if(posts.get()>0)row() else ""}]}}"""
                "/v5/user/query-api" -> """{"retCode":0,"result":{"readOnly":0,"permissions":{"Spot":["SpotTrade"]}}}"""
                "/v5/market/instruments-info" -> """{"retCode":0,"result":{"list":[{"status":"Trading","lotSizeFilter":{"basePrecision":"0.01"},"priceFilter":{"tickSize":"0.0001"}}]}}"""
                "/v5/account/wallet-balance" -> """{"retCode":0,"result":{"list":[{"coin":[{"coin":"RENDER","walletBalance":"100","availableToWithdraw":"100","locked":"0"}]}]}}"""
                "/v5/order/create" -> {
                    assertEquals(link,JSONObject(x.requestBody.bufferedReader().readText()).getString("orderLinkId"))
                    posts.incrementAndGet();code=500;"""{"retCode":10000,"retMsg":"lost response"}"""
                }
                else -> { code=404; "{}" }
            }
            val bytes=body.toByteArray();x.sendResponseHeaders(code,bytes.size.toLong());x.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client=BybitTradeClient("test-key","test-secret","http://127.0.0.1:${server.address.port}")
            val result=client.execute(proposal){reserved.incrementAndGet()}
            assertEquals("confirmed-id",result.orderId);assertEquals("New",result.orderStatus)
            assertEquals(1,posts.get());assertEquals(1,reserved.get())
        } finally { server.stop(0) }
    }
}
