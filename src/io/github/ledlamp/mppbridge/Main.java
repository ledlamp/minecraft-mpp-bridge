package io.github.ledlamp.mppbridge;

import java.io.UnsupportedEncodingException;
//import java.awt.Color;
import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import com.multiplayerpiano.multiplayerpiano.MPP.Callback;
import com.multiplayerpiano.multiplayerpiano.MPP.Client;
import org.mctourney.autoreferee.util.ColorConverter;

public class Main extends JavaPlugin implements Listener {

	ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	Client MPPclient;
	Boolean bridgeMPPchat = true;
	String lastChannel_id;
	
	
	
	
	
	@Override
	public void onEnable() {
		getServer().getPluginManager().registerEvents(this, this);
	}
	@Override
	public void onDisable() {
		if (MPPclient != null) MPPclient.stop();
		executor.shutdown();
	}

	
	
	
	
	
	private void initializeMPPclient(String channel) {
		if (channel.isEmpty()) channel = "lobby";
		
		MPPclient = new Client(URI.create("ws://www.multiplayerpiano.com:443"));
		MPPclient.setChannel(channel, null);
		MPPclient.start();

		MPPclient.on("connect", new Callback() {
			public void call(Object... args) {
				MPPclient.on("ch",  new Callback() {
					public void call(Object... args) {
						broadcast("Connected to room " + MPPclient.channel.optString("_id") + " as user " + MPPclient.getOwnParticipant().optString("name"));
						MPPclient.off("ch", this);
					}
				});
			}
		});
		MPPclient.on("disconnect", new Callback() {
			public void call(Object... args) {
				String str = (String) args[0];
				broadcast("Disconnected " + (str.isEmpty() ? "" : ": " + str));
			}
		});
		/*MPPclient.on("status", new Callback() {
			public void call(Object... args) {
				getLogger().info("Status: " + (String) args[0]);
			}
		});*/
		lastChannel_id = channel;
		MPPclient.on("ch", new Callback() {
			public void call(Object... args) {
				JSONObject msg = (JSONObject)args[0];
                JSONObject ch = msg.optJSONObject("ch");
                String _id = ch.optString("_id");
                if (!_id.equals(lastChannel_id)) {
                		broadcast("Channel changed from " + lastChannel_id + " to " + _id + ".");
                		lastChannel_id = _id;
                }
			}
		});
		
		MPPclient.on("n", new Callback() {
			public void call(Object... args) {
				JSONObject msg = (JSONObject)args[0];
				long now = System.currentTimeMillis();
				long t = msg.optLong("t", now) - MPPclient.serverTimeOffset + 1000 - System.currentTimeMillis();
				JSONArray notes = msg.optJSONArray("n");
				for(int i = 0; i < notes.length(); i++) {
					JSONObject note = notes.optJSONObject(i);
					long ms = t + note.optLong("d", 0L);
					if (ms < 0) ms = 0; else if (ms > 10000) continue;
					if (note.optLong("s") == 1) {
						// key release
						// .stopSound() is no good
					} else {
						// key press
						float vel = (float)note.optDouble("v", 0.5);
						if (vel < 0) vel = 0; else if (vel > 1) vel = 1;
						final float velf = vel;
						String sound = "mpp."+note.optString("n").replace('-', '.');
						executor.schedule(new Runnable() {
							public void run(){
								for(Player player : Bukkit.getOnlinePlayers()){
									player.playSound(player.getLocation(), sound, SoundCategory.RECORDS, velf, 1);
								}
							}
						}, ms, TimeUnit.MILLISECONDS);
					}
				}
			}
		});
		MPPclient.on("a", new Callback() {
			public void call(Object... args) {
				if (!bridgeMPPchat) return;
				JSONObject msg = (JSONObject)args[0];
				JSONObject participant = msg.optJSONObject("p");
				String _id = participant.optString("_id");
				String name = participant.optString("name");
				String message = msg.optString("a");
				if (message.startsWith("\ufffc")) return;
				//String colorChar = convertHexColorToNearestMinecraftColor(participant.optString("color")); // TODO
				String colorChar = getParticipantColor(_id);
				broadcast("§"+colorChar+"§l"+name+": §r§"+colorChar+message);
				//broadcast("§l"+name+": §r"+message);
			}
		});
	}
	
	
	
	
	private void sendChatToMPP(String message) {
		if (MPPclient == null || !bridgeMPPchat) return;
		message = "\ufffc" + message;
		message = ChatColor.stripColor(message);
		MPPclient.send(new JSONObject().put("m", "a").put("message", message));
	}
	@EventHandler
	public void onChatMessage(AsyncPlayerChatEvent event) {
		sendChatToMPP("<" + event.getPlayer().getName() + "> " + event.getMessage());
	}
	@EventHandler
	public void onPlayerDeath(PlayerDeathEvent event) {
		sendChatToMPP(event.getDeathMessage());
	}
	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		sendChatToMPP(event.getJoinMessage());
	}
	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		sendChatToMPP(event.getQuitMessage());
	}
	@EventHandler
	public void onPlayerAchievement(PlayerAdvancementDoneEvent event) {
		sendChatToMPP(event.getPlayer().getName() + " has made the advancement " + event.getAdvancement().toString()); //TODO how to get name of achievement??
	}
	
	
	
	
	
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		String cmd = command.getName().toLowerCase();
		
		
		if (cmd.equals("mppconnect")) {

			if (MPPclient != null) {
				MPPclient.setChannel(merge(args), null);
				MPPclient.start();
				return true;
			}

			sender.sendMessage("Initializing MPP client...");
			initializeMPPclient(merge(args));
			
			return true;
		}
		
		
		if (cmd.equals("mpptogglechat")) {
			if (bridgeMPPchat) {
				broadcast("Chat bridging disabled.");
				bridgeMPPchat = false;
			} else {
				bridgeMPPchat = true;
				broadcast("Chat bridging enabled.");
			}
			return true;
		}
		
		
		
		
		
		
		
		
		// commands after this require connected mpp client ////////////////////////////////////
		if (MPPclient == null) {
			sender.sendMessage("The MPP client hasn't been initialized yet.");
			return true;
		} else if (!MPPclient.isConnected()) {
			sender.sendMessage("The MPP client is not connected.");
			return true;
		}
	
		
		
		
		if (cmd.equals("mppdisconnect")) {
			MPPclient.stop();
			return true;
		}
		
		
	
		
		if (cmd.equals("mpppress")) {
			MPPclient.startNote(args[0], Double.valueOf(args[1]));
			return true;
		}
		if (cmd.equals("mpprelease")) {
			MPPclient.stopNote(args[0]);
			return true;
		}
		
		
		
		if (cmd.equals("mppsay")) {
			if (args.length == 0) return false;
			MPPclient.send(new JSONObject().put("m", "a").put("message", merge(args))); 
			return true;
		}
		
		if (cmd.equals("mppsetname")) {
			if (args.length == 0) return false;
			MPPclient.send(new JSONObject().put("m", "userset").put("set", new JSONObject().put("name", merge(args))));
			sender.sendMessage("ok");
			return true;
		}
		
		
		
		
		
		
		
		
		if (cmd.equals("mppinfo")) {
			sender.sendMessage("§3MPPbridge is connected to room §l" + MPPclient.channel.optString("_id"));
			sender.sendMessage("§l"+MPPclient.countParticipants()+"§r people are playing:");
			String participants = "";
			JSONObject ppl = MPPclient.ppl;
			for (int i = 0; i < ppl.names().length() ; i++) {
				JSONObject participant = ppl.optJSONObject(ppl.names().getString(i));
				String name = participant.optString("name");
				String color = getParticipantColor(participant.optString("_id"));
				participants += "§"+color+name+"§r, ";
			}
			sender.sendMessage(participants);
		}
		
		
		
		if (cmd.equals("mppurl")) {
			String room = MPPclient.channel.optString("_id");
			try {
				String url = "§9§nhttp://www.multiplayerpiano.com/" + URLEncoder.encode(room, "UTF-8");
				sender.sendMessage(url);
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			}
		}
		
		
		
		
		
		
		
		
		
		
		
		
		
		return false;
	}

	
	
	
	

	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	
	private String merge(String[] args) {
		String string = "";
		for (int i = 0; i < args.length; i++) {
			string += args[i] + " ";
		}
		string = string.trim();
		return string;
	}
	
	private void broadcast(String message) {
		if (bridgeMPPchat) getServer().broadcastMessage("§3[MPP]§r " + message);
		else getLogger().info(message);
	}
	
	
	
	
	
	String chatColors[] = {
			"1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"
	};
	Map<String, String> participant2color = new HashMap<>();
	private String getParticipantColor(String _id) {
		String color = participant2color.get(_id);
		if (color == null) {
			String randColor = chatColors[new Random().nextInt(chatColors.length)];
			participant2color.put(_id, randColor);
			return randColor;
		}
		return color;
	}
	
	
	
	
	
	
	
	
	
	// garbage below
	/*
	
	
	
	
	
	/*private String filterFormattingCodes(String string) { // dont work
		String formattingCodes[] = {
			"§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9", "§a", "§b", "§c", "§d", "§e", "§f"
		};
		for (int i = 0; i < formattingCodes.length; i++) {
			string = string.replaceAll("/"+formattingCodes[i]+"/g", "");
		}
		return string;
	}
	
	private String convertHexColorToNearestMinecraftColor(String hex) {
		
		String[][] mcColorCodeMap = {
		    {"000000", "0"},
		    {"000AAA", "1"},
		    {"00AA00", "2"},
		    {"00AAAA", "3"},
		    {"AA0000", "4"},
		    {"AA00AA", "5"},
		    {"FFAA00", "6"},
		    {"AAAAAA", "7"},
		    {"555555", "8"},
		    {"5555FF", "9"},
		    {"55FF55", "a"},
		    {"55FFFF", "b"},
		    {"FF5555", "c"},
		    {"FF55FF", "d"},
		    {"FFFF55", "e"},
		    {"FFFFFF", "f"}
		};
		
		java.awt.Color rgbColor = hex2Rgb(hex);
		int red = rgbColor.getRed();
		int green = rgbColor.getGreen();
		int blue = rgbColor.getBlue();
		
		Map<String, Integer> colorDiffs = new HashMap<>();

		for (int i = 0; i < mcColorCodeMap.length; i++) {
			String[] link = mcColorCodeMap[i];
			String mcHex = link[0]; String mcChar = link[1];
			
			java.awt.Color mcRgbColor = hex2Rgb(mcHex);
			int mcRed = mcRgbColor.getRed();
			int mcGreen = mcRgbColor.getGreen();
			int mcBlue = mcRgbColor.getBlue();
			
			int redDiff = mcRed - red;
			int greenDiff = mcGreen - green;
			int blueDiff = mcBlue - blue;
			int totalDiff = redDiff + greenDiff + blueDiff;
			
			colorDiffs.put(mcChar, totalDiff);
		}
		
		int lowestDiff = 99999999;
		for (Integer diff : colorDiffs.values()) {
			if (diff < lowestDiff) lowestDiff = diff;
		}
		
		String theClosestMatchingMcColorChar = "k";
		for (Map.Entry<String, Integer> entry : colorDiffs.entrySet()) {
			String mcChar = entry.getKey(); int diff = entry.getValue();
			if (lowestDiff == diff) {
				theClosestMatchingMcColorChar = mcChar; break;
			}
		}
		
		return theClosestMatchingMcColorChar;
	}
	
	public static java.awt.Color hex2Rgb(String colorStr) {
		if (colorStr.startsWith("#"))
	    return new java.awt.Color(
	            Integer.valueOf( colorStr.substring( 1, 3 ), 16 ),
	            Integer.valueOf( colorStr.substring( 3, 5 ), 16 ),
	            Integer.valueOf( colorStr.substring( 5, 7 ), 16 ) );
		else
			return new java.awt.Color(
		            Integer.valueOf( colorStr.substring( 0, 2 ), 16 ),
		            Integer.valueOf( colorStr.substring( 2, 4 ), 16 ),
		            Integer.valueOf( colorStr.substring( 4, 6 ), 16 ) );
	}
	
	
	private char convertHexColorToNearestMinecraftColor(String hexColor) {
		java.awt.Color jColor = java.awt.Color.decode(hexColor);
		DyeColor dColor = DyeColor.getByColor(Color.fromRGB(jColor.getRed(), jColor.getGreen(), jColor.getBlue())); // null
		ChatColor cColor = ColorConverter.dyeToChat(dColor);
		return cColor.getChar();
	}*/
	
	
	
	
	
	
}