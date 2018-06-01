package io.github.ledlamp.mppbridge;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import com.multiplayerpiano.multiplayerpiano.MPP.Callback;
import com.multiplayerpiano.multiplayerpiano.MPP.Client;

public class Main extends JavaPlugin {
	ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
	Client currentClient;
	Boolean showMPPchat = true;
	
	@Override
	public void onEnable() {
		
	}
	
	@Override
	public void onDisable() {
		executor.shutdown();
	}
	
	@Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (command.getName().equalsIgnoreCase("mpp")) {
			sender.sendMessage("Multiplayer Piano is at http://www.multiplayerpiano.com/");
			if (currentClient == null) sender.sendMessage("The MPP client has yet to be initialized.");
			else {
				if (currentClient.isConnected()) {
					sender.sendMessage("Server is connected to MPP room "+ currentClient.channel.optString("_id"));
					sender.sendMessage("There are "+currentClient.countParticipants()+" participants:");
					String participants = "";
					JSONObject ppl = currentClient.ppl;
					for (int i = 0; i < ppl.names().length() ; i++) {
						participants += ppl.optJSONObject(ppl.names().getString(i)).optString("name") + ", ";
					}
					sender.sendMessage(participants);
				} else {
					sender.sendMessage("The MPP client is disconnected.");
				}
			}
			return true;
		}
		if (command.getName().equalsIgnoreCase("mppconnect")) {

        	String c = "";
            if (args.length > 0) {
                for (int i = 0; i < args.length; i++) {
                    c = c + args[i] + " ";
                }
                c = c.trim();
            } else {
            	c = "lobby";
            }
            final String channel = c;
        	
            if(currentClient != null) {
        		currentClient.setChannel(channel, null);
        		currentClient.start();
        		getServer().broadcastMessage("Connected to MPP room "+channel);
        		return true;
        	}
            
            sender.sendMessage("Initializing MPP client...");
            
            Client client = new Client(URI.create("ws://www.multiplayerpiano.com/"));
			client.setChannel(channel, null);
            client.start();
            
            getServer().broadcastMessage("Connected to MPP room "+channel);
            
            client.on("connect", new Callback() {
            	public void call(Object... args) {
            		getLogger().info("Connected to MPP room "+channel);
            	}
            });
            client.on("disconnect", new Callback() {
            	public void call(Object... args) {
            		getLogger().info("Disconnect from MPP room "+channel);
            		client.connect(); // quick fix for random disconnects
            	}
            });
            client.on("status", new Callback() {
            	public void call(Object... args) {
            		getLogger().info("Status: " + (String) args[0]);
            	}
            });
            client.on("n", new Callback() {
            	public void call(Object... args) {
                    JSONObject msg = (JSONObject)args[0];
                    
                    long now = System.currentTimeMillis();
                    long t = msg.optLong("t", now) - client.serverTimeOffset + 1000 - System.currentTimeMillis();
                    JSONArray notes = msg.optJSONArray("n");
                    for(int i = 0; i < notes.length(); i++) {
                    	JSONObject note = notes.optJSONObject(i);
                    	long ms = t + note.optLong("d", 0L);
                    	if (ms < 0) ms = 0; else if (ms > 10000) continue;
                    	if (note.optLong("s") == 1) {
                    		// key release
                    		// .stopSound("mpp."+note.optString("n"), SoundCategory.RECORDS); // no good
                    	} else {
                    		// key press
                    		float vel = (float)note.optDouble("v", 0.5);
                    		if (vel < 0) vel = 0; else if (vel > 1) vel = 1;
                    		final float velf = vel;
                    		Runnable task = new Runnable() {
                    			public void run(){
                    				for(Player p : Bukkit.getOnlinePlayers()){
                                    	p.playSound(p.getLocation(), "mpp."+note.optString("n"), SoundCategory.RECORDS, velf, 1);
                                    }
                    			}
                    		};
                    		executor.schedule(task, ms, TimeUnit.MILLISECONDS);
                    	}
                    }
                }
            });
            client.on("a", new Callback() {
            	public void call(Object... args) {
            		if (!showMPPchat) return;
            		JSONObject msg = (JSONObject)args[0];
            		JSONObject participant = msg.optJSONObject("p");
            		String message = msg.optString("a");
            		getServer().broadcastMessage("[MPP] §l"+participant.optString("name")+":§r "+message);
            	}
            });
            currentClient = client;
            return true;
        }
        if (command.getName().equalsIgnoreCase("mppdisconnect")) {
        	if (currentClient == null) {
        		sender.sendMessage("No client is running.");
        		return true;
        	}
        	currentClient.stop();
        	getServer().broadcastMessage("MPP bridge stopped.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("mppsay")) {
        	if (currentClient == null) {
        		sender.sendMessage("No client is running.");
        		return true;
        	}
        	if (args.length == 0) return false;
        	String txt = "";
        	for (int i = 0; i < args.length; i++) {
        	    txt = txt + args[i] + " ";
        	}
        	txt = txt.trim();
        	JSONObject message = new JSONObject().put("m", "a").put("message", txt);
            currentClient.send(message); 
            return true;
        }
        if (command.getName().equalsIgnoreCase("mpptogglechat")) {
            if (showMPPchat) {
            	showMPPchat = false;
            	getServer().broadcastMessage("MPP Chat Display Disabled.");
            } else {
            	showMPPchat = true;
            	getServer().broadcastMessage("MPP Chat Display Enabled.");
            }
            return true;
        }
        return false;
    }
}
