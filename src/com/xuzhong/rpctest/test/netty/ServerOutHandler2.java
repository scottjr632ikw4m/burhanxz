package com.xuzhong.rpctest.test.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

public class ServerOutHandler2 extends ChannelOutboundHandlerAdapter{
	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		// TODO Auto-generated method stub
		System.out.println("out2::" + msg);
		ctx.write(msg, promise);
	}
	@Override
	public void flush(ChannelHandlerContext ctx) throws Exception {

		System.out.println("out2##flush");
	}
}
