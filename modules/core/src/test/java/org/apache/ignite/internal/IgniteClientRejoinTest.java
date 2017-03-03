/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteInClosure;
import org.apache.ignite.plugin.extensions.communication.Message;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.spi.IgniteSpiOperationTimeoutException;
import org.apache.ignite.spi.IgniteSpiOperationTimeoutHelper;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.messages.TcpDiscoveryAbstractMessage;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;

/**
 * Tests client to be able restore connection to cluster if coordination is not available.
 */
public class IgniteClientRejoinTest extends GridCommonAbstractTest {
    /** Block. */
    private volatile boolean block;

    /** Coordinator. */
    private volatile ClusterNode crd;

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        System.setProperty("IGNITE_SKIP_CONFIGURATION_CONSISTENCY_CHECK", "true");
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        System.clearProperty("IGNITE_SKIP_CONFIGURATION_CONSISTENCY_CHECK");
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        if (gridName.contains("client")) {
            cfg.setCommunicationSpi(new TcpCommunicationSpi());

            TcpDiscoverySpi spi = (TcpDiscoverySpi)cfg.getDiscoverySpi();
            DiscoverySpi dspi = new DiscoverySpi();

            dspi.setIpFinder(spi.getIpFinder());

            cfg.setDiscoverySpi(dspi);

            cfg.setClientMode(true);
        }

        return cfg;
    }

    /**
     * @throws Exception If failed.
     */
    public void testReconnect() throws Exception {
        Ignite srv1 = startGrid("server1");

        crd = ((IgniteKernal)srv1).localNode();

        Ignite srv2 = startGrid("server2");

        block = true;

        IgniteInternalFuture<Boolean> fut = GridTestUtils.runAsync(new Callable<Boolean>() {
            @Override public Boolean call() throws Exception {
                Random rnd = new Random();

                U.sleep((rnd.nextInt(15) + 30) * 1000);

                block = false;

                System.out.println("ALLOW connection to coordinator.");

                return true;
            }
        });

        Ignite client = startGrid("client");


        assert fut.get();

        IgniteCache<Integer, Integer> cache = client.getOrCreateCache("some");

        for (int i = 0; i < 100; i++)
            cache.put(i, i);

        for (int i = 0; i < 100; i++)
            assert i == cache.get(i);

        Collection<ClusterNode> clients = client.cluster().forClients().nodes();

        assertEquals("Clients: " + clients, 1, clients.size());
        assertEquals(1, srv1.cluster().forClients().nodes().size());
        assertEquals(1, srv2.cluster().forClients().nodes().size());
    }

    /**
     * @throws Exception If failed.
     */
    public void testManyClientsReconnect() throws Exception {
        Ignite srv1 = startGrid("server1");

        crd = ((IgniteKernal)srv1).localNode();

        Ignite srv2 = startGrid("server2");

        block = true;

        List<IgniteInternalFuture<Ignite>> futs = new ArrayList<>();

        final CountDownLatch latch = new CountDownLatch(1);

        final int CLIENTS_NUM = 5;

        for (int i = 0; i < CLIENTS_NUM; i++) {
            final int idx = i;

            IgniteInternalFuture<Ignite> fut = GridTestUtils.runAsync(new Callable<Ignite>() {
                @Override public Ignite call() throws Exception {
                    latch.await();

                    return startGrid("client" + idx);
                }
            });

            futs.add(fut);
        }

        GridTestUtils.runAsync(new Callable<Boolean>() {
            @Override public Boolean call() throws Exception {
                latch.countDown();

                Random rnd = new Random();

                U.sleep((rnd.nextInt(15) + 15) * 1000);

                block = false;

                System.out.println(">>> ALLOW connection to coordinator.");

                return true;
            }
        });

        for (IgniteInternalFuture<Ignite> clientFut : futs) {
            Ignite client = clientFut.get();

            IgniteCache<Integer, Integer> cache = client.getOrCreateCache(client.name());

            for (int i = 0; i < 100; i++)
                cache.put(i, i);

            for (int i = 0; i < 100; i++)
                assert i == cache.get(i);
        }

        assertEquals(CLIENTS_NUM, srv1.cluster().forClients().nodes().size());
        assertEquals(CLIENTS_NUM, srv2.cluster().forClients().nodes().size());
    }

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return 2 * 60_000;
    }

    /**
     *
     */
    private class TcpCommunicationSpi extends org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi {
        /** {@inheritDoc} */
        @Override public void sendMessage(ClusterNode node, Message msg) throws IgniteSpiException {
            if (block && node.id().equals(crd.id()))
                throw new IgniteSpiException(new SocketException("Test communication exception"));

            super.sendMessage(node, msg);
        }

        /** {@inheritDoc} */
        @Override public void sendMessage(ClusterNode node, Message msg,
            IgniteInClosure<IgniteException> ackC) throws IgniteSpiException {
            if (block && node.id().equals(crd.id()))
                throw new IgniteSpiException(new SocketException("Test communication exception"));

            super.sendMessage(node, msg, ackC);
        }
    }

    /**
     *
     */
    private class DiscoverySpi extends TcpDiscoverySpi {
        /** {@inheritDoc} */
        @Override protected void writeToSocket(Socket sock, TcpDiscoveryAbstractMessage msg, byte[] data,
            long timeout) throws IOException {
            if (block && sock.getPort() == 47500)
                throw new SocketException("Test discovery exception");

            super.writeToSocket(sock, msg, data, timeout);
        }

        /** {@inheritDoc} */
        @Override protected void writeToSocket(Socket sock, TcpDiscoveryAbstractMessage msg,
            long timeout) throws IOException, IgniteCheckedException {
            if (block && sock.getPort() == 47500)
                throw new SocketException("Test discovery exception");

            super.writeToSocket(sock, msg, timeout);
        }

        /** {@inheritDoc} */
        @Override protected void writeToSocket(Socket sock, OutputStream out, TcpDiscoveryAbstractMessage msg,
            long timeout) throws IOException, IgniteCheckedException {
            if (block && sock.getPort() == 47500)
                throw new SocketException("Test discovery exception");

            super.writeToSocket(sock, out, msg, timeout);
        }

        /** {@inheritDoc} */
        @Override protected void writeToSocket(TcpDiscoveryAbstractMessage msg, Socket sock, int res,
            long timeout) throws IOException {
            if (block && sock.getPort() == 47500)
                throw new SocketException("Test discovery exception");

            super.writeToSocket(msg, sock, res, timeout);
        }

        /** {@inheritDoc} */
        @Override protected Socket openSocket(Socket sock, InetSocketAddress remAddr,
            IgniteSpiOperationTimeoutHelper timeoutHelper) throws IOException, IgniteSpiOperationTimeoutException {
            if (block && sock.getPort() == 47500)
                throw new SocketException("Test discovery exception");

            return super.openSocket(sock, remAddr, timeoutHelper);
        }
    }
}