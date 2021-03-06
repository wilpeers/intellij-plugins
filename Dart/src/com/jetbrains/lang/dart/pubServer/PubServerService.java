package com.jetbrains.lang.dart.pubServer;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.AsyncResult;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ConcurrentHashSet;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.net.NetUtils;
import com.jetbrains.lang.dart.sdk.DartSdk;
import com.jetbrains.lang.dart.sdk.DartSdkUtil;
import icons.DartIcons;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCounted;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.builtInWebServer.ConsoleManager;
import org.jetbrains.builtInWebServer.NetService;
import org.jetbrains.io.*;

import javax.swing.*;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

final class PubServerService extends NetService {
  private static final Logger LOG = Logger.getInstance(PubServerService.class.getName());

  private volatile VirtualFile firstServedDir;

  private final Bootstrap bootstrap = NettyUtil.nioClientBootstrap();

  private final ConcurrentMap<Channel, ChannelHandlerContext> serverToClientContext = ContainerUtil.newConcurrentMap();
  // client context could point to several server channels (because client connected to IDEA server, not to proxied server)
  private final MultiMap<ChannelHandlerContext, Channel> clientContextToServerChannels = MultiMap.createConcurrentSet();

  private final ConcurrentHashSet<Channel> freeServerChannels = new ConcurrentHashSet<Channel>();
  private final ChannelInboundHandlerAdapter clientChannelStateHandler = new ClientChannelStateHandler();
  private final ChannelRegistrar serverChannelRegistrar = new ChannelRegistrar();

  private final ConcurrentMap<VirtualFile, InetSocketAddress> servedDirToSocketAddress = ContainerUtil.newConcurrentMap();

  private final ChannelFutureListener serverChannelCloseListener = new ChannelFutureListener() {
    @Override
    public void operationComplete(ChannelFuture future) throws Exception {
      Channel channel = future.channel();
      freeServerChannels.remove(channel);

      ChannelHandlerContext clientContext = serverToClientContext.remove(channel);
      if (clientContext != null) {
        clientContextToServerChannels.remove(clientContext, channel);
        sendBadGateway(clientContext.channel());
      }
    }
  };

  public PubServerService(@NotNull Project project, @NotNull ConsoleManager consoleManager) {
    super(project, consoleManager);

    bootstrap.handler(new ChannelInitializer() {
      @Override
      protected void initChannel(Channel channel) throws Exception {
        channel.pipeline().addLast(serverChannelRegistrar, new HttpClientCodec());
        channel.pipeline().addLast(new PubServeChannelHandler(), ChannelExceptionHandler.getInstance());
      }
    });
  }

  @Override
  @NotNull
  protected String getConsoleToolWindowId() {
    return "Pub Serve";
  }

  @Override
  @NotNull
  protected Icon getConsoleToolWindowIcon() {
    return DartIcons.Dart_13;
  }

  @NotNull
  @Override
  public ActionGroup getConsoleToolWindowActions() {
    return new DefaultActionGroup(ActionManager.getInstance().getAction("Dart.stop.pub.server"));
  }

  public boolean isPubServerProcessAlive() {
    return processHandler.has() && !processHandler.get().getResult().isProcessTerminated();
  }

  public void sendToPubServer(@NotNull final ChannelHandlerContext clientContext,
                              @NotNull final FullHttpRequest clientRequest,
                              @NotNull final VirtualFile servedDir,
                              @NotNull final String pathForPubServer) {
    clientRequest.retain();

    if (processHandler.has()) {
      sendToServer(servedDir, clientContext, clientRequest, pathForPubServer);
    }
    else {
      firstServedDir = servedDir;

      processHandler.get().doWhenDone(new Runnable() {
        @Override
        public void run() {
          sendToServer(servedDir, clientContext, clientRequest, pathForPubServer);
        }
      }).doWhenRejected(new Runnable() {
        @Override
        public void run() {
          sendBadGateway(clientContext.channel());
        }
      });
    }
  }

  @Override
  @Nullable
  protected OSProcessHandler createProcessHandler(@NotNull final Project project, final int port) throws ExecutionException {
    final DartSdk dartSdk = DartSdk.getGlobalDartSdk();
    if (dartSdk == null) return null;

    final GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(DartSdkUtil.getPubPath(dartSdk));
    commandLine.addParameter("serve");
    commandLine.addParameter(firstServedDir.getName());
    commandLine.addParameter("--port=" + String.valueOf(port));
    //commandLine.addParameter("--admin-port=" + String.valueOf(PubServerManager.findOneMoreAvailablePort(port))); // todo uncomment and use

    commandLine.setWorkDirectory(firstServedDir.getParent().getPath());

    return new OSProcessHandler(commandLine);
  }

  @Override
  protected void connectToProcess(@NotNull final AsyncResult<OSProcessHandler> asyncResult,
                                  final int port,
                                  @NotNull final OSProcessHandler processHandler,
                                  @NotNull final Consumer<String> errorOutputConsumer) {
    InetSocketAddress firstPubServerAddress = new InetSocketAddress(NetUtils.getLoopbackAddress(), port);
    InetSocketAddress old = servedDirToSocketAddress.put(firstServedDir, firstPubServerAddress);
    LOG.assertTrue(old == null);

    super.connectToProcess(asyncResult, port, processHandler, errorOutputConsumer);
  }

  @SuppressWarnings({"MethodMayBeStatic", "UnusedParameters"})
  private void serveDirAndSendRequest(@NotNull final ChannelHandlerContext clientContext,
                                      @NotNull final FullHttpRequest clientRequest,
                                      @NotNull final VirtualFile servedDir,
                                      @NotNull final String pathForPubServer) {
    throw new UnsupportedOperationException(); // todo this code is not reachable because of commented out /*.getParent()*/ in PubServerManager.send()
  }

  static void sendBadGateway(@NotNull final Channel channel) {
    if (channel.isActive()) {
      Responses.sendStatus(HttpResponseStatus.BAD_GATEWAY, channel);
    }
  }

  @Override
  protected void closeProcessConnections() {
    servedDirToSocketAddress.clear();

    ChannelHandlerContext[] clientContexts;
    try {
      Collection<ChannelHandlerContext> clients = serverToClientContext.values();
      clientContexts = clients.toArray(new ChannelHandlerContext[clients.size()]);
      freeServerChannels.clear();
      serverToClientContext.clear();
    }
    finally {
      serverChannelRegistrar.close();
    }

    for (ChannelHandlerContext context : clientContexts) {
      try {
        sendBadGateway(context.channel());
      }
      catch (Exception e) {
        LOG.error(e);
      }
    }
  }

  private static void connect(@NotNull final Bootstrap bootstrap,
                              @NotNull final SocketAddress remoteAddress,
                              final @NotNull Consumer<Channel> channelConsumer) {
    final AtomicInteger attemptCounter = new AtomicInteger(1);
    bootstrap.connect(remoteAddress).addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (future.isSuccess()) {
          channelConsumer.consume(future.channel());
        }
        else {
          int attemptCount = attemptCounter.incrementAndGet();
          if (attemptCount > NettyUtil.DEFAULT_CONNECT_ATTEMPT_COUNT) {
            channelConsumer.consume(null);
          }
          else {
            Thread.sleep(attemptCount * NettyUtil.MIN_START_TIME);
            bootstrap.connect(remoteAddress).addListener(this);
          }
        }
      }
    });
  }

  void sendToServer(@NotNull final VirtualFile servedDir,
                    @NotNull final ChannelHandlerContext clientContext,
                    @NotNull final FullHttpRequest clientRequest,
                    @NotNull final String pathToPubServe) {
    InetSocketAddress serverAddress = servedDirToSocketAddress.get(servedDir);
    if (serverAddress == null) {
      serveDirAndSendRequest(clientContext, clientRequest, servedDir, pathToPubServe);
    }

    Channel serverChannel = findActiveServerChannel(clientContext, serverAddress);
    if (serverChannel == null) {
      serverAddress = servedDirToSocketAddress.get(servedDir);
      if (serverAddress == null) {
        serveDirAndSendRequest(clientContext, clientRequest, servedDir, pathToPubServe);
      }

      serverChannel = findFreeServerChannel(serverAddress);
      if (serverChannel != null) {
        clientContextToServerChannels.putValue(clientContext, serverChannel);
      }
    }

    if (serverChannel == null) {
      connect(bootstrap, serverAddress, new Consumer<Channel>() {
        @Override
        public void consume(final Channel serverChannel) {
          if (serverChannel == null) {
            sendBadGateway(clientContext.channel());
          }
          else {
            serverChannel.closeFuture().addListener(serverChannelCloseListener);
            ChannelHandlerContext oldClientContext = serverToClientContext.put(serverChannel, clientContext);
            LOG.assertTrue(oldClientContext == null);
            clientContextToServerChannels.putValue(clientContext, serverChannel);

            clientContext.channel().pipeline().addLast(clientChannelStateHandler);
            sendToServer(clientRequest, pathToPubServe, serverChannel);
          }
        }
      });
    }
    else {
      sendToServer(clientRequest, pathToPubServe, serverChannel);
    }
  }

  @Nullable
  private Channel findActiveServerChannel(@NotNull ChannelHandlerContext clientContext, @NotNull InetSocketAddress serverAddress) {
    for (Channel serverChannel : clientContextToServerChannels.get(clientContext)) {
      if (serverChannel.remoteAddress().equals(serverAddress)) {
        return serverChannel;
      }
    }
    return null;
  }

  @Nullable
  private Channel findFreeServerChannel(@NotNull InetSocketAddress serverAddress) {
    Iterator<Channel> iterator = freeServerChannels.iterator();
    if (iterator.hasNext()) {
      Channel serverChannel = iterator.next();
      if (serverAddress.getPort() == ((InetSocketAddress)serverChannel.remoteAddress()).getPort()) {
        iterator.remove();
        return serverChannel;
      }
    }
    return null;
  }

  private static void sendToServer(@NotNull FullHttpRequest clientRequest, @NotNull String pathToPubServe, @NotNull Channel serverChannel) {
    // duplicate - content will be shared (opposite to copy), so, we use duplicate. see ByteBuf javadoc.
    FullHttpRequest request = clientRequest.duplicate().setUri(pathToPubServe);
    // regardless of client, we always keep connection to server
    HttpHeaders.setKeepAlive(request, true);
    InetSocketAddress serverAddress = (InetSocketAddress)serverChannel.remoteAddress();
    HttpHeaders.setHost(request, serverAddress.getAddress().getHostAddress() + ':' + serverAddress.getPort());
    serverChannel.writeAndFlush(request);
  }

  @ChannelHandler.Sharable
  private class PubServeChannelHandler extends SimpleChannelInboundHandlerAdapter<HttpObject> {
    public PubServeChannelHandler() {
      super(false);
    }

    @Override
    protected void messageReceived(@NotNull ChannelHandlerContext context, @NotNull HttpObject message) throws Exception {
      ChannelHandlerContext clientContext = serverToClientContext.get(context.channel());
      if (clientContext != null && clientContext.channel().isActive()) {
        clientContext.channel().writeAndFlush(message);
      }
      else if (message instanceof ReferenceCounted) {
        ((ReferenceCounted)message).release();
      }
    }
  }

  @ChannelHandler.Sharable
  private class ClientChannelStateHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
      super.channelInactive(context);

      Collection<Channel> serverChannels = clientContextToServerChannels.remove(context);
      if (!ContainerUtil.isEmpty(serverChannels)) {
        for (Channel serverChannel : serverChannels) {
          if (serverToClientContext.remove(serverChannel, context)) {
            freeServerChannels.add(serverChannel);
          }
        }
      }
    }
  }
}