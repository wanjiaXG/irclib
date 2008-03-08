package org.schwering.irc.manager;

import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Vector;

import org.schwering.irc.lib.IRCEventListener;
import org.schwering.irc.lib.IRCModeParser;
import org.schwering.irc.lib.IRCUser;
import org.schwering.irc.lib.IRCConstants;
import org.schwering.irc.lib.IRCUtil;
import org.schwering.irc.manager.event.BanlistEvent;
import org.schwering.irc.manager.event.ChannelModeEvent;
import org.schwering.irc.manager.event.ConnectionEvent;
import org.schwering.irc.manager.event.ErrorEvent;
import org.schwering.irc.manager.event.InvitationEvent;
import org.schwering.irc.manager.event.MOTDEvent;
import org.schwering.irc.manager.event.MessageEvent;
import org.schwering.irc.manager.event.NamesEvent;
import org.schwering.irc.manager.event.NickEvent;
import org.schwering.irc.manager.event.NumericEvent;
import org.schwering.irc.manager.event.PingEvent;
import org.schwering.irc.manager.event.TopicEvent;
import org.schwering.irc.manager.event.UnexpectedEvent;
import org.schwering.irc.manager.event.UserModeEvent;
import org.schwering.irc.manager.event.UserParticipationEvent;
import org.schwering.irc.manager.event.UserStatusEvent;
import org.schwering.irc.manager.event.WhoisEvent;

/**
 * Distributes the <code>IRCEventListener</code> events to the respective
 * listeners of a <code>Connection</code> object.
 * @author Christoph Schwering &lt;schwering@gmail.com&gt;
 * @since 2.00
 * @version 1.00
 */
class BasicListener implements IRCEventListener {
	private Connection owner;
	private boolean registered = false;
	
	public BasicListener(Connection owner) {
		this.owner = owner;
	}

	/* Connection events */
	
	public void onRegistered() {
		registered = true;
		owner.fireConnectionEstablished(new ConnectionEvent(owner));
	}

	public void onDisconnected() {
		registered = false;
		owner.fireConnectionLost(new ConnectionEvent(owner));
		owner.clearChannels();
	}

	public void onError(String msg) {
		ErrorEvent event = new ErrorEvent(owner, msg);
		owner.fireErrorReceived(event);
	}

	public void onInvite(String chan, IRCUser user, String passiveNick) {
		User invitingUser = owner.resolveUser(user);
		User invitedUser = owner.resolveUser(passiveNick);
		Channel channel = owner.resolveChannel(chan);
		InvitationEvent event = new InvitationEvent(owner, channel, 
				invitingUser, invitedUser);
		owner.fireInvited(event);
	}

	public void onPing(String ping) {
		PingEvent event = new PingEvent(owner, ping);
		owner.firePingReceived(event);
	}

	public void onMode(IRCUser user, String passiveNick, String mode) {
		User activeUser = owner.resolveUser(user);
		User passiveUser = owner.resolveUser(passiveNick);
		UserModeEvent event = new UserModeEvent(owner, activeUser, passiveUser,
				mode);
		owner.fireUserModeReceived(event);
	}

	public void onError(int num, String msg) {
		if (!registered) { // ask for a new nickname
			switch (num) {
			case IRCConstants.ERR_NICKNAMEINUSE:
			case IRCConstants.ERR_ERRONEUSNICKNAME:
			case IRCConstants.ERR_NONICKNAMEGIVEN:
				String newNick = owner.getNickGenerator().createNewNick();
				if (newNick != null) {
					owner.getIRCConnection().doNick(newNick);
				} else {
					owner.getIRCConnection().doQuit();
				}
			}
		} else {
			NumericEvent event = new NumericEvent(owner, num, null, msg); 
			owner.fireNumericErrorReceived(event);
		}
	}
	
	public void onReply(int num, String val, String msg) {
		if (num == IRCConstants.RPL_TOPIC) {
			handleTopicReply(val, msg);
		} else if (num == IRCConstants.RPL_NOTOPIC) {
			Channel channel = owner.resolveChannel(val);
			Topic topic = new Topic(channel, null, null, null);
			TopicEvent event = new TopicEvent(owner, topic);
			if (owner.hasChannel(channel)) {
				channel.fireTopicReceived(event);
			} else {
				owner.fireTopicReceived(event);
			}
		} else if (num == IRCConstants.RPL_NAMREPLY) {
			handleNamesReply(val, msg);
		} else if (num == IRCConstants.RPL_WHOREPLY) {
			handleWhoReply(val, msg);
		} else if (num == IRCConstants.RPL_WHOWASUSER) {
			// terminated by RPL_ENDOFWHOWAS
		} else if (isReplyNumber(num)) {
			handleWhoisReply(num, val, msg);
		} else if (num == IRCConstants.RPL_AWAY) {
			handleAwayReply(val, msg);
		} else if (num == IRCConstants.RPL_LIST) {
			// terminated by RPL_ENDOFLIST
		} else if (num == IRCConstants.RPL_BANLIST) {
			handleBanlistReply(val, msg);
		} else if (num == IRCConstants.RPL_LINKS) {
			// terminated by RPL_ENDOFLINKS
		} else if (num == IRCConstants.RPL_MOTDSTART) {
			handleMOTDReply(msg);
		}
		NumericEvent event = new NumericEvent(owner, num, val, msg); 
		owner.fireNumericReplyReceived(event);
	}
	
	private static boolean isReplyNumber(int num) {
		return num == IRCConstants.RPL_WHOISUSER 
		|| num == IRCConstants.RPL_WHOISSERVER
		|| num == IRCConstants.RPL_WHOISCHANNELS
		|| num == IRCConstants.RPL_WHOISOPERATOR
		|| num == IRCConstants.RPL_WHOISIDLE
		|| num == IRCConstants.RPL_WHOISCHANNELS;
	}
	
	private static String skipFirstToken(String str) {
		str = str.trim();
		boolean found = false;
		for (int i = 0; i < str.length(); i++) {
			if (!found && Character.isWhitespace(str.charAt(i))) {
				found = true;
			} else if (found && !Character.isWhitespace(str.charAt(i))) {
				return str.substring(i);
			}
		}
		return "";
	}
	
	private static String getFirstToken(String str) {
		str = str.trim();
		for (int i = 0; i < str.length(); i++) {
			if (Character.isWhitespace(str.charAt(i))) {
				return str.substring(0, i-1);
			}
		}
		return str;
	}
	
	private void handleTopicReply(String val, String msg) {
		val = skipFirstToken(val); // skip own name
		StringTokenizer valTok = new StringTokenizer(val);
		final Channel channel = owner.resolveChannel(valTok.nextToken());
		final Message message = (msg == null || msg.trim().length() > 0) ? new Message(msg) : null;
		
		new NumericEventWaiter(owner) {
			private User user = null;
			private Date date = null;
			
			protected boolean handle(NumericEvent event) {
				if (event.getNumber() == IRCConstants.RPL_TOPICINFO) {
					String val = skipFirstToken(event.getValue()); // skip own name
					StringTokenizer valTok = new StringTokenizer(val);
					Channel secondChannel = owner.resolveChannel(valTok.nextToken());
					if (secondChannel.isSame(channel)) {
						user = owner.resolveUser(valTok.nextToken());
						try {
							String seconds = event.getMessage();
							long millis = Long.parseLong(seconds) * 1000;
							date = new Date(millis);
						} catch (Exception exc) {
							date = null;
						}
					}
					return false;
				} else {
					return true;
				}
			}
			
			protected void fire() {
				Topic topic = new Topic(channel, message, user, date);
				TopicEvent event = new TopicEvent(owner, topic);
				if (owner.hasChannel(channel)) {
					channel.setTopic(topic);
					channel.fireTopicReceived(event);
				} else {
					owner.fireTopicReceived(event);
				}
			}
		};
	}
	
	private Set blockedNamesChannels = new HashSet();
	
	private void handleNamesReply(String val, String msg) {
		val = skipFirstToken(val); // skip own name
		final Channel channel = owner.resolveChannel(val);
		if (blockedNamesChannels.contains(channel)) {
			return;
		}
		blockedNamesChannels.add(channel);
		final List names = new Vector();
		handleNamesLine(names, channel, msg);
		new NumericEventWaiter(owner) {
			protected boolean handle(NumericEvent event) {
				if (event.getNumber() == IRCConstants.RPL_NAMREPLY || event.getNumber() == IRCConstants.RPL_ENDOFNAMES) {
					String val = skipFirstToken(event.getValue()); // skip own name
					Channel secondChannel = owner.resolveChannel(val);
					boolean rightChannel = channel.isSame(secondChannel);
					if (rightChannel && event.getNumber() == IRCConstants.RPL_NAMREPLY) {
						handleNamesLine(names, channel, event.getMessage());
						return true;
					} else if (rightChannel && event.getNumber() == IRCConstants.RPL_ENDOFNAMES) {
						blockedNamesChannels.remove(channel);
						return false;
					}
				}
				return true;
			}
			
			protected void fire() {
				NamesEvent event = new NamesEvent(owner, channel, names);
				if (owner.hasChannel(channel)) {
					channel.fireNamesReceived(event);
				} else {
					owner.fireNamesReceived(event);
				}
			}
		};
	}
	
	private void handleNamesLine(List names, Channel channel, String msg) {
		StringTokenizer tok = new StringTokenizer(msg);
		tok.nextToken(); // skip own nick
		tok.nextToken(); // skip @, * or = (XXX meaning?)
		while (tok.hasMoreTokens()) {
			String name = tok.nextToken();
			int status = ChannelUser.NONE;
			if (name.charAt(0) == '@') {
				status = ChannelUser.OPERATOR;
				name = name.substring(1);
			} else if (name.charAt(0) == '+') {
				status = ChannelUser.VOICED;
				name = name.substring(1);
			}
			User user = owner.resolveUser(name);
			ChannelUser chanUser = new ChannelUser(channel, user, status);
			names.add(chanUser);
			ChannelUser origChanUser = channel.getUser(chanUser);
			if (origChanUser != null
					&& origChanUser.getStatus() != chanUser.getStatus()) {
				UserStatusEvent event = new UserStatusEvent(owner, channel, 
						chanUser);
				channel.fireUserStatusChanged(event);
			}
		}
	}
	
	private Set blockedWhoChannels = new HashSet();
	
	private void handleWhoReply(String val, String msg) {
		val = skipFirstToken(val); // skip own name
		final Channel channel = owner.resolveChannel(val);
		if (blockedWhoChannels.contains(channel)) {
			return;
		}
		blockedWhoChannels.add(channel);
		final List who = new Vector();
		handleWhoLine(who, msg);
		new NumericEventWaiter(owner) {
			protected boolean handle(NumericEvent event) {
				if (event.getNumber() == IRCConstants.RPL_WHOREPLY || event.getNumber() == IRCConstants.RPL_ENDOFWHO) {
					String val = skipFirstToken(event.getValue()); // skip own name
					Channel secondChannel = owner.resolveChannel(val);
					boolean rightChannel = channel.isSame(secondChannel);
					if (rightChannel && event.getNumber() == IRCConstants.RPL_WHOREPLY) {
						handleWhoLine(who, event.getMessage());
						return true;
					} else if (rightChannel && event.getNumber() == IRCConstants.RPL_ENDOFWHO) {
						blockedWhoChannels.remove(channel);
						return false;
					}
				}
				return true;
			}
			
			protected void fire() {
				NamesEvent event = new NamesEvent(owner, channel, who);
				if (owner.hasChannel(channel)) {
					channel.fireNamesReceived(event);
				} else {
					owner.fireNamesReceived(event);
				}
			}
		};
	}
	
	private void handleWhoLine(List who, String msg) {
		// TODO implement, format: <channel> <user> <host> <server> <nick> <H|G>[*][@|+] :<hopcount> <real name>
	}
	
	private Set blockedWhoisUsers = new HashSet();
	
	private void handleWhoisReply(int num, String val, String msg) {
		val = skipFirstToken(val); // skip own name
		StringTokenizer valTok = new StringTokenizer(val);
		String nick = valTok.nextToken();
		final User user = owner.resolveUser(nick);
		if (blockedWhoisUsers.contains(user)) {
			return;
		}
		final Whois whois = new Whois();
		blockedWhoisUsers.add(user);
		handleWhoisLine(whois, num, val, msg);
		new NumericEventWaiter(owner) {
			protected boolean handle(NumericEvent event) {
				if (isReplyNumber(event.getNumber())) {
					String val = skipFirstToken(event.getValue());
					StringTokenizer valTok = new StringTokenizer(val);
					String nick = valTok.nextToken();
					User secondUser = owner.resolveUser(nick);
					boolean rightUser = user.equals(secondUser);
					if (rightUser && isReplyNumber(event.getNumber()) && event.getNumber() != IRCConstants.RPL_ENDOFWHOIS) {
						handleWhoisLine(whois, event.getNumber(), val, event.getMessage());
					} else if (rightUser && event.getNumber() == IRCConstants.RPL_AWAY) {
						String awayMsg = event.getMessage();
						whois.awayMessage = (awayMsg != null) ? new Message(awayMsg) : null;
					} else if (rightUser && event.getNumber() == IRCConstants.RPL_AUTHNAME) {
						whois.authName = valTok.nextToken();
					} else if (rightUser && event.getNumber() == IRCConstants.RPL_ENDOFWHOIS) {
						blockedWhoisUsers.remove(user);
						return false;
					}
				}
				return true;
			}
			
			protected void fire() {
				if (whois.username != null && whois.host != null) {
					user.update(whois.username, whois.host);
				}
				Date date = null;
				if (whois.idle) {
					user.setAway(true);
					if (whois.millis != -1) {
						date = new Date(whois.millis);
					}
				}
				WhoisEvent event = new WhoisEvent(owner, user, whois.realName, 
						whois.authName, whois.server, whois.serverInfo,
						whois.operator, whois.idle, date, whois.awayMessage,
						whois.channels);
				owner.fireWhoisReceived(event);
			}
		};
	}
	
	private static class Whois {
		String username, host, realName, server, serverInfo, authName;
		boolean operator = false, idle = false;
		Message awayMessage;
		long millis;
		List channels = new LinkedList();
	}
	
	private void handleWhoisLine(Whois whois, int num, String val, String msg) {
		val = skipFirstToken(val);
		if (num == IRCConstants.RPL_WHOISUSER) {
			StringTokenizer valTok = new StringTokenizer(val);
			whois.username = valTok.nextToken();
			whois.host = valTok.nextToken();
			whois.realName = msg;
		} else if (num == IRCConstants.RPL_WHOISSERVER) {
			whois.server = val;
			whois.serverInfo = msg;
		} else if (num == IRCConstants.RPL_WHOISOPERATOR) {
			whois.operator = true;
		} else if (num == IRCConstants.RPL_WHOISIDLE) {
			whois.idle = true;
			try {
				whois.millis = Long.parseLong(val) * 1000;
			} catch (NumberFormatException exc) {
				whois.millis = -1;
				exc.printStackTrace();
			}
		} else if (num == IRCConstants.RPL_WHOISCHANNELS) {
			StringTokenizer msgTok = new StringTokenizer(msg);
			while (msgTok.hasMoreTokens()) {
				String tok = msgTok.nextToken();
				whois.channels.add(tok);
			}
		} else if (num == IRCConstants.RPL_AUTHNAME) {
			
		}
	}
	
	private Set blockedAwayUsers = new HashSet();
	
	private void handleAwayReply(String val, String msg) {
		String nick = skipFirstToken(val);
		User user = owner.resolveUser(nick);
		if (blockedAwayUsers.contains(user)) { // blocked by WHOIS handler
			return;
		}
	}
	
	private Set blockedBanlistChannels = new HashSet();
	
	private void handleBanlistReply(String val, String msg) {
		val = skipFirstToken(val); // skip own name
		StringTokenizer valTok = new StringTokenizer(val);
		final Channel channel = owner.resolveChannel(valTok.nextToken());
		if (blockedBanlistChannels.contains(channel)) {
			return;
		}
		blockedBanlistChannels.add(channel);
		final List banIDs = new Vector();
		banIDs.add(msg);
		new NumericEventWaiter(owner) {
			protected boolean handle(NumericEvent event) {
				String val = skipFirstToken(event.getValue()); // skip own name
				StringTokenizer valTok = new StringTokenizer(val);
				Channel secondChannel = owner.resolveChannel(valTok.nextToken());
				boolean rightChannel = channel.isSame(secondChannel);
				if (rightChannel && event.getNumber() == IRCConstants.RPL_BANLIST) {
					banIDs.add(event.getMessage());
					return true;
				} else if (rightChannel && event.getNumber() == IRCConstants.RPL_ENDOFBANLIST) {
					blockedBanlistChannels.remove(channel);
					return false;
				} else {
					return true;
				}
			}
			
			protected void fire() {
				BanlistEvent event = new BanlistEvent(owner, channel, banIDs);
				if (owner.hasChannel(channel)) {
					channel.setBanIDs(banIDs);
					channel.fireBanlistReceived(event);
				} else {
					owner.fireBanlistReceived(event);
				}
			}
		};
	}
	
	private void handleMOTDReply(String msg) {
		final List motd = new LinkedList();
		motd.add(msg);
		new NumericEventWaiter(owner) {
			protected boolean handle(NumericEvent event) {
				if (event.getNumber() == IRCConstants.RPL_MOTD) {
					motd.add(event.getMessage());
					return true;
				} else if (event.getNumber() == IRCConstants.RPL_ENDOFMOTD) {
					return false;
				} else {
					return true;
				}
			}
			
			protected void fire() {
				MOTDEvent event = new MOTDEvent(owner, motd);
				owner.fireMotdReceived(event);
			}
		};
	}

	public void onNick(IRCUser user, String newNick) {
		User oldUser = owner.resolveUser(user);
		User newUser = new User(oldUser, newNick);
		NickEvent event = new NickEvent(owner, oldUser, newUser);
		for (Iterator it = owner.getChannels().iterator(); it.hasNext(); ) {
			Channel channel = (Channel)it.next();
			if (channel.hasUser(oldUser)) {
				channel.removeUser(oldUser);
				channel.addUser(newUser);
				channel.fireNickChanged(event);
			}
		}
	}

	public void onQuit(IRCUser ircUser, String msg) {
		User user = owner.resolveUser(ircUser);
		if (user.getNick().equals(owner.getNick())) {
			for (Iterator it = owner.getChannels().iterator(); it.hasNext(); ) {
				Channel channel = (Channel)it.next();
				UserParticipationEvent event = new UserParticipationEvent(owner,
						channel, user, UserParticipationEvent.QUIT, 
						new Message(msg));
				owner.fireChannelLeft(event);
				owner.removeChannel(channel);
			}
		} else {
			for (Iterator it = owner.getChannels().iterator(); it.hasNext(); ) {
				Channel channel = (Channel)it.next();
				if (channel.hasUser(user)) {
					UserParticipationEvent event = new UserParticipationEvent(owner,
							channel, user, UserParticipationEvent.QUIT, 
							new Message(msg));
					channel.fireUserLeft(event);
					channel.removeUser(user);
				}
			}
		}
	}

	public void unknown(String prefix, String command, String middle,
			String trailing) {
		Object[] args = new Object[] { prefix, command, middle };
		UnexpectedEvent event = new UnexpectedEvent(owner, "unknown", args);
		owner.fireUnexpectedEventReceived(event);
	}
	
	public void onNotice(String target, IRCUser ircUser, String msg) {
		MessageEvent event;
		User sender = owner.resolveUser(ircUser);
		if (IRCUtil.isChan(target)) {
			Channel channel = owner.resolveChannel(target);
			event = new MessageEvent(owner, sender, channel, msg);
		} else {
			User user = owner.resolveUser(target);
			event = new MessageEvent(owner, sender, user, new Message(msg));
		}
		owner.fireNoticeReceived(event);
	}

	public void onPrivmsg(String target, IRCUser ircUser, String msg) {
		MessageEvent event;
		User sender = owner.resolveUser(ircUser);
		if (IRCUtil.isChan(target)) {
			Channel channel = owner.resolveChannel(target);
			event = new MessageEvent(owner, sender, channel, msg);
		} else {
			User user = owner.resolveUser(target);
			event = new MessageEvent(owner, sender, user, new Message(msg));
		}
		owner.fireNoticeReceived(event);
	}
	
	/* Channel events */

	public void onJoin(String chan, IRCUser ircUser) {
		Channel channel = owner.resolveChannel(chan);
		User user = owner.resolveUser(ircUser);
		UserParticipationEvent event = new UserParticipationEvent(owner, 
				channel, user, UserParticipationEvent.JOIN);
		if (user.getNick().equals(owner.getNick())) {
			owner.addChannel(channel);
			channel.addUser(user);
			owner.fireChannelJoined(event);
			if (owner.getRequestModes()) {
				owner.getIRCConnection().doMode(chan);
				owner.getIRCConnection().doMode(chan, "+b");
			}
		} else {
			channel.addUser(user);
			channel.fireUserJoined(event);
		}
	}

	public void onPart(String chan, IRCUser ircUser, String msg) {
		Channel channel = owner.resolveChannel(chan);
		User user = owner.resolveUser(ircUser);
		UserParticipationEvent event = new UserParticipationEvent(owner, 
				channel, user, UserParticipationEvent.PART, 
				new Message(msg));
		if (user.getNick().equals(owner.getNick())) {
			owner.fireChannelLeft(event);
			channel.removeUser(user);
			owner.removeChannel(channel);
		} else {
			channel.fireUserLeft(event);
			channel.removeUser(user);
		}
	}

	public void onKick(String chan, IRCUser ircUser, String passiveNick, 
			String msg) {
		Channel channel = owner.resolveChannel(chan);
		User user = owner.resolveUser(ircUser);
		User kickingUser = owner.resolveUser(passiveNick);
		User kickedUser = owner.resolveUser(passiveNick);
		UserParticipationEvent event = new UserParticipationEvent(owner, 
				channel, user, UserParticipationEvent.KICK, 
				new Message(msg), kickingUser);
		if (user.getNick().equals(owner.getNick())) {
			owner.fireChannelLeft(event);
			channel.removeUser(kickedUser);
			owner.removeChannel(channel);
		} else {
			channel.fireUserLeft(event);
			channel.removeUser(kickedUser);
		}
	}
	
	public void onTopic(String chan, IRCUser ircUser, String msg) {
		Channel channel = owner.resolveChannel(chan);
		Message message = (msg == null || msg.trim().length() > 0) ? new Message(msg) : null;
		User user = owner.resolveUser(ircUser);
		Date date = new Date();
		Topic topic = new Topic(channel, message, user, date);
		channel.setTopic(topic);
		TopicEvent event = new TopicEvent(owner, topic);
		channel.fireTopicReceived(event);
	}

	public void onMode(String chan, IRCUser ircUser, IRCModeParser modeParser) {
		Channel channel = owner.resolveChannel(chan);
		User user = owner.resolveUser(ircUser);
		ChannelModeEvent event = new ChannelModeEvent(owner, channel, user, 
				modeParser);
		channel.fireChannelModeReceived(event);
		for (int i = 0; i < modeParser.getCount(); i++) { // user status changed
			int mode = modeParser.getModeAt(i);
			if (mode == 'o' || mode == 'v') {
				int status = (mode == 'o') ? ChannelUser.OPERATOR
						: ChannelUser.VOICED;
				int oper = modeParser.getOperatorAt(i);
				String arg = modeParser.getArgAt(i);
				ChannelUser chanUser = channel.getUser(arg);
				int oldStatus = chanUser.getStatus();
				if (oper == '+') {
					chanUser.addStatus(status);
				} else if (oper == '-') {
					chanUser.removeStatus(status);
				}
				if (chanUser.getStatus() != oldStatus) {
					UserStatusEvent e = new UserStatusEvent(owner, channel, 
							chanUser);
					channel.fireUserStatusChanged(e);
				}
			}
		}
	}
}
