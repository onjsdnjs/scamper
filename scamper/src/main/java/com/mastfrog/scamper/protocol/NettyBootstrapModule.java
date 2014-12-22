package com.mastfrog.scamper.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.mastfrog.giulius.ShutdownHookRegistry;
import com.mastfrog.scamper.protocol.Message;
import com.mastfrog.util.Codec;
import de.undercouch.bson4jackson.BsonFactory;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Does the basics of initializing various Netty classes, and ensures that if
 * Dependencies.shutdown() is called, all Netty-related thread-pools and
 * connections are shut down cleanly.
 * <p>
 * If you are using ProtocolModule, it will take care of installing one of these
 * on its own.
 *
 * @author Tim Boudreau
 */
final class NettyBootstrapModule extends AbstractModule {

    private final BsonFactory bsonFactory = new BsonFactory();
    private final Class<? extends ChannelHandlerAdapter> adap;
    private final int bossThreads;
    private final int workerThreads;

    public NettyBootstrapModule(Class<? extends ChannelHandlerAdapter> adap) {
        this(adap, 2, -1);
    }

    public NettyBootstrapModule(Class<? extends ChannelHandlerAdapter> adap, int bossThreads, int workerThreads) {
        this.adap = adap;
        this.bossThreads = bossThreads;
        this.workerThreads = workerThreads;
    }

    @Override
    protected void configure() {
        bind(ChannelHandlerAdapter.class).to(adap);
        bind(BsonFactory.class).toInstance(bsonFactory);
        bind(ObjectMapper.class).toInstance(new ObjectMapper(bsonFactory));
        bind(EventLoopGroup.class).annotatedWith(Names.named("boss")).toInstance(new NioEventLoopGroup(bossThreads));
        bind(EventLoopGroup.class).annotatedWith(Names.named("worker")).toInstance(workerThreads == -1 ? new NioEventLoopGroup() : new NioEventLoopGroup(workerThreads));
        bind(ByteBufAllocator.class).toInstance(new PooledByteBufAllocator(true));
        bind(ShutdownHandler.class).asEagerSingleton();
        bind(Codec.class).to(CodecImpl.class);
    }

    static class ShutdownHandler implements Runnable {

        private final EventLoopGroup boss;
        private final EventLoopGroup worker;

        @Inject
        ShutdownHandler(@Named("boss") EventLoopGroup boss, @Named("worker") EventLoopGroup worker, ShutdownHookRegistry reg) {
            reg.add(this);
            this.boss = boss;
            this.worker = worker;
        }

        @Override
        public void run() {
            try {
                boss.shutdownGracefully();
            } finally {
                worker.shutdownGracefully();
            }
        }
    }

    private static final class CodecImpl implements Codec {

        private final ObjectMapper mapper;

        @Inject
        public CodecImpl(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public <T> String writeValueAsString(T t) throws IOException {
            return mapper.writeValueAsString(t);
        }

        @Override
        public <T> void writeValue(T t, OutputStream out) throws IOException {
            if (t instanceof Message) {
                throw new IllegalArgumentException("Serializing message is a bug");
            }
            mapper.writeValue(out, t);
        }

        @Override
        public <T> byte[] writeValueAsBytes(T t) throws IOException {
            return mapper.writeValueAsBytes(t);
        }

        @Override
        public <T> T readValue(InputStream in, Class<T> type) throws IOException {
            return mapper.readValue(in, type);
        }
    }
}