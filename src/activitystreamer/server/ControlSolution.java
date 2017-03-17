package activitystreamer.server;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.google.gson.Gson;

import activitystreamer.util.Settings;

@SuppressWarnings("unused")
public class ControlSolution extends Control {
	private static final Logger log = LogManager.getLogger();
	// UserName and secret in hashmap
	private static HashMap<String,String> userSec;
	// UserName with login flag hashmap
	private static HashMap<String, Integer> userLogin;
	// UserName with connection hashmap
	private static HashMap<Connection, String> userCon;
	// UserName with login flag for server in the system
	private static HashMap<String, String> userSysLogin;
	
	private static String serverSecret;
	static int load;
	String id;
	// Server connections list
	private static ArrayList<ServerList> serverList;
	// Keeping track of lock allowed mesages
	private static HashMap<String, Integer> lockAllow;
	private static Connection conForRegister;
	
	/*
	 * additional variables as needed
	 */
	
	// since control and its subclasses are singleton, we get the singleton this way
	public static ControlSolution getInstance() {
		if(control==null){
			control=new ControlSolution();
		} 
		return (ControlSolution) control;
	}
	
	public ControlSolution() {
		super();
		userSec = new HashMap<String, String>();
		userLogin = new HashMap<String, Integer>();
		userCon = new HashMap<Connection, String>();
		serverList = new ArrayList<ServerList>(); 
		lockAllow = new HashMap<String, Integer>();
		userSysLogin = new HashMap<String, String>();
		
		serverSecret = null;
		load = 0;
		conForRegister = null;
		
		id = Settings.nextSecret();
		// Server details getting stored. Port and it's corresponding secret.
		// This can be used as server id.
		
		int localport = Settings.getLocalPort();
		int remoteport = Settings.getRemotePort();
		if (Settings.getSecret() == null) {
			String secret = Settings.nextSecret();
			Settings.setSecret(secret);
			System.out.println("Root Server Secret: " + secret);
	      
		}
		System.out.println("Server id: " + id);
		
		/*
		 * Do some further initialization here if necessary
		 */

		
		// check if we should initiate a connection and do so if necessary
		initiateConnection();
		// start the server's activity loop
		// it will call doActivity every few seconds
		start();
	}
	
	
	/*
	 * a new incoming connection
	 */
	@Override
	public Connection incomingConnection(Socket s) throws IOException{
		boolean term=true;
		Connection con = super.incomingConnection(s);
		/*
		 * do additional things here
		 */
		
		return con;
	}
	
	/*
	 * a new outgoing connection
	 */
	@Override
	public Connection outgoingConnection(Socket s) throws IOException{
		Connection con = super.outgoingConnection(s);
		/*
		 * do additional things here
		 */
		log.debug("In outgoing connection controls solution");
		sendToAnotherServer(con);
		
		return con;
	}
	
	@SuppressWarnings({ "unchecked", "unused" })
	public void sendToAnotherServer(Connection con) {
		String secret;
		JSONObject obj = new JSONObject();
		int logout_flag = 0;
		try {
			
			if ((Settings.getSecret().length() != 0)) {
				obj.put("command", "AUTHENTICATE");
				obj.put("secret", Settings.getSecret());
			} else if ((Settings.getUsername().length() != 0) && (Settings.getSecret().length() == 0)){
				obj.put("command", "INVALID_MESSAGE");
				obj.put("info", "the received message did not contain a command");
				logout_flag = 1;
			}
		}
		catch(Exception ec) {
			ec.printStackTrace();
		}
		
		sendMsg(con, obj, logout_flag);
	}

	public void sendMsg(Connection con, JSONObject obj, int logout) {
		Gson gs=new Gson();
		String msg = gs.toJson(obj);
		con.writeMsg(msg);
		if (logout == 1) {
			connectionClosed(con);
			con.closeCon();
		}
	}
	
	/*
	 * the connection has been closed
	 */
	@Override
	public void connectionClosed(Connection con){
		super.connectionClosed(con);
		/*
		 * do additional things here
		 */
	}
	
	
	/*
	 * process incoming msg, from connection con
	 * return true if the connection should be closed, false otherwise
	 */
	@Override
	public synchronized boolean process(Connection con,String msg){
		boolean term=false;
		String msg1 = msg.trim().replaceAll("\r","").replaceAll("\n","").replaceAll("\t", "");
		//log.debug(msg1);
		Gson gs=new Gson();
		
		JSONParser parser = new JSONParser();
		Object obj = null;
		try {
			obj = parser.parse(msg1);
		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		JSONObject jsonObject = (JSONObject) obj;

		term=parseMsg(jsonObject, con);
		
		return term;
	}

	@SuppressWarnings("unchecked")
	public synchronized boolean parseMsg(JSONObject jsonObject, Connection con) {
	
		String command = (String) jsonObject.get("command");
		String serverId;
		boolean term=false;
		JSONObject obj = new JSONObject();
		if (command == null) {
			return term;
		}
		
		if (command.compareTo("LOGIN") == 0) {
			term = login(jsonObject, con);
		} else if (command.compareTo("REGISTER") == 0) {
			lockAllow = new HashMap<String, Integer>();
			conForRegister = con; 
			term = register(jsonObject, con);
			
		} else if (command.compareTo("LOGOUT") == 0) {
			term = logout(jsonObject, con);
		} else if (command.compareTo("AUTHENTICATE") == 0) {
			authenticate(jsonObject, con);
		} else if (command.compareTo("AUTHENTICATE_FAIL") == 0) {
			authenticate_fail(jsonObject , con);
		} else if (command.compareTo("SERVER_ANNOUNCE") == 0) {
			server_announce(jsonObject, con);
		} else if (command.compareTo("ACTIVITY_MESSAGE") == 0) {
			term = activity_message(jsonObject, con);
		} else if (command.compareTo("ACTIVITY_BROADCAST") == 0) {
			activity_broadcast(jsonObject, con);
		} else if(command.compareTo("LOCK_REQUEST") == 0) {
			lock_request(jsonObject, con);
		} else if (command.compareTo("LOCK_ALLOWED") == 0) 
		{
			// Keep count of the lock allowed messages
			 serverId = (String) jsonObject.get("serverid");
			if(serverId.compareTo(id)==0)
			{
				lockAllow.put((String)jsonObject.get("server"), 1);
				if (lockAllow.size() == serverList.size())
				{
					if(lockAllow.containsValue(0))
					{
						JSONObject obj1 = new JSONObject();
						obj1.put("command", "REGISTER_FAILED");
						obj1.put("info", (String) jsonObject.get("username") + " is already registered with the system");
						incrementLoad();
						Gson gs=new Gson();
						String msg = gs.toJson(obj1);
						conForRegister.writeMsg(msg);
						conForRegister.closeCon();
						connectionClosed(conForRegister);
						//term = true;
					}
					else {
						// Now register it
						String secret = (String) jsonObject.get("secret");
						String userName = (String) jsonObject.get("username");
						userSec.put(userName, secret);
						obj.put("command", "REGISTER_SUCCESS");
						obj.put("info", "register success for " + userName);
						
						// Send it to client
						Gson gs=new Gson();
						String msg = gs.toJson(obj);
						if (conForRegister != null)
							conForRegister.writeMsg(msg);
					}
				}
			} else
			{
				lock_allowed(jsonObject, con);
			}
		} else if (command.compareTo("LOCK_DENIED") == 0) {
			 serverId = (String) jsonObject.get("serverid");
			//lockAllow.put(serverId, 1);
			if(serverId.compareTo(id)==0)
			{
				lockAllow.put((String)jsonObject.get("server"), 0);
				
				// dont register
				if (lockAllow.size() == serverList.size())
				{
					//System.out.println("Inside inside 0");
					if(lockAllow.containsValue(0))
					{
						JSONObject obj1 = new JSONObject();
						obj1.put("command", "REGISTER_FAILED");
						obj1.put("info", (String) jsonObject.get("username") + " is already registered with the system");
						incrementLoad();
						Gson gs=new Gson();
						String msg = gs.toJson(obj1);
						log.debug(conForRegister.getSocket());
						
						conForRegister.writeMsg(msg);
						conForRegister.closeCon();
						connectionClosed(conForRegister);
						//term = true;
					}
				}
				
				
				
			}
			else
				lock_denied(jsonObject, con);
		} else {
			obj.put("command", "INVALID_MESSAGE");
			obj.put("info", "No userName provided");
			
			Gson gs=new Gson();
			String msg = gs.toJson(obj);
			con.writeMsg(msg);
			term = true;
		}
		return term;
	}
	
	@SuppressWarnings("static-access")
	private void server_announce(JSONObject jsonobject, Connection con) {
		// TODO Auto-generated method stub
		long load = -1, port = -1;
		int flag = 0;
		String command = (String) jsonobject.get("command");
		String id_serv = (String) jsonobject.get("id");
		load = (long) jsonobject.get("load");
		String hostname = (String) jsonobject.get("hostname");
		port = (long) jsonobject.get("port");
		ServerList list1 = new ServerList();
		ArrayList <Connection> conn = getServIncomConnections();
		
		if ((command != null) && (command.length() > 0)) {
			//System.out.println(command);
			
			// Add the objects to the serverList
			
			for(ServerList s : serverList)
			{
				if((s.getServerID() != null ) && (s.getServerID().compareTo(id_serv)) == 0) {
					// setting already present flag 
					flag = 1;
					// updating load
					s.setClientLoad((int)load);
				}
			}
			if (flag == 0) {
				if ((id_serv != null) && (id_serv.length() > 0)) {
					list1.setServerID(id_serv);
				}
				if (load != -1) {
					list1.setClientLoad((int)load);
				}
				if (hostname != null) {
					list1.setHost(hostname);
				}
				if (port != -1) {
					list1.setPort((int)port);
				}
				
				log.info("Adding to server list");
				if (list1 != null) {
					serverList.add(list1);
				}	
			}
			
			for(ServerList ss : serverList) {
				if(ss.getServerID().compareTo(id_serv) == 0) {
					forward_server_announce(ss, con, jsonobject);
				}
			}
		}
	}

	@SuppressWarnings({ "unchecked", "static-access" })
	public void forward_server_announce(ServerList list,Connection con, JSONObject jsonobject)
	{
		JSONObject obj = new JSONObject();
		obj.put("command", "SERVER_ANNOUNCE");
		obj.put("id",list.getServerID());		
		obj.put("load",list.getClientLoad());
		obj.put("hostname", list.getHost());
		obj.put("port",list.getPort());
		
		ArrayList <Connection> conn = getServIncomConnections();
		for(Connection server_con : conn)
		{
			if(server_con!=con)
			{
				Gson gs=new Gson();
				String msg = gs.toJson(obj);
				server_con.writeMsg(msg);
			}
		}
		
		ArrayList <Connection> conne = getServConnections();
		for(Connection server_con : conne)
		{
			if(server_con!=con)
			{
				Gson gs=new Gson();
				String msg = gs.toJson(obj);
				server_con.writeMsg(msg);
			}
		}
		
	}
	
	@SuppressWarnings("unchecked")
	public boolean activity_message(JSONObject jsonObject,Connection con) {
		JSONObject obj = new JSONObject();
		String secret;
		int logout = 0;
		boolean term = false;
		String userName = (String) jsonObject.get("username");
		JSONObject activity = (JSONObject) jsonObject.get("activity");
		// Check  if the username is not anonymous or if the username and secret do not
		// match the logged in user, or if the user has not logged in yet. 
		if (userName.length() != 0) {
			secret = (String) jsonObject.get("secret");
			if (userName.compareTo("anonymous") == 0) {
				if (secret == null) {
					//success. Activity Broadcast
					activity_broadcast_send(jsonObject, con);
				} else {
					obj.put("command", "AUTHENTICATION_FAIL");
					obj.put("info", "Activity Object cannot be processed: " + userName);
					logout = 1;
				}
			} else {
				if (secret.length() != 0) {
					if ( ((userSec.containsKey(userName) == true) 
							&& (secret.compareTo(userSec.get(userName)) == 0))
							|| ((userLogin.containsKey(userName) == true)
							&& (userLogin.get(userName) == 1))) {
						//success. Activity Broadcast
						activity_broadcast_send(jsonObject, con);
						
					} else {						
						obj.put("command", "AUTHENTICATION_FAIL");
						obj.put("info", "Activity Object cannot be processed: " + userName);
						logout = 1;
					}
				} else {
					obj.put("command", "INVALID_MESSAGE");
					obj.put("info", "Activity OBject cannot be processed: " + userName);
					logout = 1;
				}
			}
		} else {
			obj.put("command", "INVALID_MESSAGE");
			obj.put("info", "No userName provided");
			logout = 1;
		}
		
		Gson gs=new Gson();
		String msg = gs.toJson(obj);
		//System.out.println(msg);
		con.writeMsg(msg);
		if (logout == 1) {
			String userName1;
			connectionClosed(con);
			con.closeCon();
			// Remove user from login hash table
			userName1 = userCon.get(con);
			userCon.remove(con);
			userLogin.remove(userName1);
			// To decrement client load
			decrementLoad();
			//term = true;
		}
		return term;
	}
	
	@SuppressWarnings("unchecked")
	public void activity_broadcast_send(JSONObject jsonObject,Connection con) {
		// To process the object it will add a single field to the object called authenticated_user
		JSONObject obj = new JSONObject();
		String userName = (String) jsonObject.get("username");
		//String activity = (String) jsonObject.get("activity");
		JSONObject activity = (JSONObject) jsonObject.get("activity");
		
		obj.put("command", "ACTIVITY_BROADCAST");
		obj.put("activity", activity);
		obj.put("authenticated_user", userName);
		// Now broadcast to all servers and their clients.
		ArrayList <Connection> conn = getConnections();
		for(Connection conne : conn) {
			if (conne != con) {
				Gson gs=new Gson();
				String msg = gs.toJson(obj);
				conne.writeMsg(msg);
			}
		}
		ArrayList <Connection> connn = getServConnections();
		for(Connection connne : connn) {
			if (connne != con) {
				Gson gs=new Gson();
				String msg = gs.toJson(obj);
				connne.writeMsg(msg);
			}
		}
		
	}
	
	@SuppressWarnings("unchecked")
	public void activity_broadcast(JSONObject jsonObject,Connection con) {
		// To process the object it will add a single field to the object called authenticated_user
		JSONObject obj = new JSONObject();
		String userName = (String) jsonObject.get("authenticated_user");
		JSONObject activity = (JSONObject) jsonObject.get("activity");
		
		obj.put("command", "ACTIVITY_BROADCAST");
		obj.put("activity", activity);
		obj.put("authenticated_user", userName);
		
		// Now broadcast to all servers and their clients.
		ArrayList <Connection> conn = getConnections();
		for(Connection conne : conn) {
			if (conne != con) {
				Gson gs=new Gson();
				String msg = gs.toJson(obj);
				conne.writeMsg(msg);
			}
		}
		ArrayList <Connection> connn = getServConnections();
		for(Connection connne : connn) {
			if (connne != con) {
				Gson gs=new Gson();
				String msg = gs.toJson(obj);
				connne.writeMsg(msg);
			}
		}
		
	}
	
	/*
	 * Called once every few seconds
	 * Return true if server should shut down, false otherwise
	 */
	@SuppressWarnings("unchecked")
	@Override
	public boolean doActivity(){
		/*
		 * do additional work here
		 * return true/false as appropriate
		 */
		String loadStr = Integer.toString(load);
		
		// The client load should be broadcasted within the network.
		JSONObject obj = new JSONObject();
		obj.put("command", "SERVER_ANNOUNCE");
		obj.put("id", id);		
		obj.put("load", getLoad());
		obj.put("hostname", Settings.getLocalHostname());
		obj.put("port", Settings.getLocalPort());
		
		System.out.println("SERVER LOAD: " + getLoad());

		ArrayList <Connection> conn = getServIncomConnections();
		for(Connection conne : conn) {
			Gson gs=new Gson();
			String msg = gs.toJson(obj);
			conne.writeMsg(msg);
		}
		ArrayList <Connection> connn = getServConnections();
		for(Connection connne : connn) {
			Gson gs=new Gson();
			String msg = gs.toJson(obj);
			connne.writeMsg(msg);
		}
		
		return false;
	}
	
	/*
	 * Other methods as needed
	 */

	@SuppressWarnings("unchecked")
	public boolean login(JSONObject jsonObject, Connection con) {
		JSONObject obj = new JSONObject();
		String secret;
		String userName = (String) jsonObject.get("username");
		int logout_flag = 0;
		int loginSuccess = 0;
		boolean term = false;
		if (userName.length() != 0) {
			secret = (String) jsonObject.get("secret");
			if (userName.compareTo("anonymous") == 0) {
				if (secret == null) {
						obj.put("command", "LOGIN_SUCCESS");
						obj.put("info", "logged in as user " + userName);
						ArrayList <Connection> conn=getConnections();
						incrementLoad();
						//System.out.println("Load for this server is: " + conn.size());
						loginSuccess = 1;
				} else {
					obj.put("command", "INVALID_MESSAGE");
					obj.put("info", "invalid logged in as user " + userName);
					logout_flag = 1;
				}
			} else {
				if (secret.length() != 0) {
					if ((userSec.containsKey(userName)) 
							&& (secret.compareTo(userSec.get(userName)) == 0) ) {
						obj.put("command", "LOGIN_SUCCESS");
						obj.put("info", "logged in as user " + userName);
						userLogin.put(userName, 1);
						userCon.put(con, userName);
						ArrayList <Connection> conn=getConnections();
						incrementLoad();
						//System.out.println("Load is : " + getLoad());
						loginSuccess = 1;
					} else if ((userSysLogin.containsKey(userName)) &&
							(secret.compareTo(userSysLogin.get(userName)) == 0)) {
						obj.put("command", "LOGIN_SUCCESS");
						obj.put("info", "logged in as user " + userName);
						userLogin.put(userName, 1);
						userCon.put(con, userName);
						//System.out.println("here wew areraersdkjgh");
						incrementLoad();
						loginSuccess = 1;
					} else {
						//System.out.println("secret : " + secret);
						//System.out.println("userSysLogin.get(userName) : " + userSysLogin.get(userName) );
						//System.out.println("contains or not : " + userSysLogin.containsKey(userName));
						obj.put("command", "LOGIN_FAILED");
						obj.put("info", "attempt to login with wrong userName/secret");
						logout_flag = 1;
					}
				} else {
					obj.put("command", "INVALID_MESSAGE");
					obj.put("info", "Login failed as no secret provided");
					logout_flag = 1;
				}
			}
		} else {
			obj.put("command", "INVALID_MESSAGE");
			obj.put("info", "No userName provided");
			logout_flag = 1;
		}
		Gson gs=new Gson();
		String msg = gs.toJson(obj);
		con.writeMsg(msg);
		if (loginSuccess == 1) {
			// After login success, try to redirect if there is any other server in the network
			// with  a load at least 2 clients less than its own.
			redirect(con);
		}
		if (logout_flag == 1) {
			connectionClosed(con);
			con.closeCon();
			incrementLoad();
			//term = true;
		}
		
		return term;
	}
	
	public static int getLoad() {
		return load;
	}

	public static void incrementLoad() {
		load++;
	}
	
	public static void decrementLoad() {
		if (getLoad() > 0)
			load--;
		else
			load = 0;
	}

	@SuppressWarnings({ "unchecked", "static-access" })
	public void redirect(Connection con) {
		JSONObject obj = new JSONObject();
		String host = null;
		int port = -1;
		int flag = 0;
		String servid;
		for(ServerList s : serverList)
		{
			if( (getLoad() - (s.getClientLoad())) >= 2) {
				log.info("current load is " + getLoad() + " and other server load is " + s.getClientLoad());
				flag = 1;
				servid = s.getServerID();
				host = s.getHost();
				port = s.getPort();
				break;
			}
		}
		if (flag == 1) {
			obj.put("command", "REDIRECT");
			obj.put("hostname", host);
			obj.put("port", port);
			Gson gs=new Gson();
			String msg = gs.toJson(obj);
			con.writeMsg(msg);
			// disconnect the client
			connectionClosed(con);
			con.closeCon();
		}
	}
	
	@SuppressWarnings("unchecked")
	public synchronized boolean register(JSONObject jsonObject, Connection con) {
		JSONObject obj = new JSONObject();
		//System.out.println("inside register");
		String secret = (String) jsonObject.get("secret");
		String userName = (String) jsonObject.get("username");
		int logout_flag = 0; 
		boolean term = false;
		
		boolean retval;
		if ((userName.length() != 0) && (secret.length() != 0)) {
				retval = userSec.containsKey(userName);
				if (retval == true) {
					if (userLogin.containsKey(userName)) {
						obj.put("command", "INVALID_MESSAGE");
						obj.put("info", userName + " has already logged in");	
						logout_flag = 1;
						Gson gs=new Gson();
						String msg = gs.toJson(obj);
						con.writeMsg(msg);
						connectionClosed(con);
						con.closeCon();
						incrementLoad();
						//term = true;
					} else {
						obj.put("command", "REGISTER_FAILED");
						obj.put("info", userName + " is already registered with the system");
						Gson gs=new Gson();
						String msg = gs.toJson(obj);
						con.writeMsg(msg);
						connectionClosed(con);
						con.closeCon();
						incrementLoad();
						//term = true;
					}
				}
				else {
						// send a lock request to all other servers and then register 
						// if the name is not registered at any server
						if (serverList.isEmpty() != true) {
							sendLockRequest(jsonObject, con);
						} else {
							userSec.put(userName, secret);
							obj.put("command", "REGISTER_SUCCESS");
							obj.put("info", "register success for " + userName);
							Gson gs=new Gson();
							String msg = gs.toJson(obj);
							con.writeMsg(msg);
						}
				}
		}
		return term;
		
	}
		
	@SuppressWarnings("unchecked")
	public synchronized void lock_request(JSONObject jsonObject, Connection con) {
		
		String userName = (String) jsonObject.get("username");
		String secret = (String) jsonObject.get("secret");
		String serverid=(String) jsonObject.get("serverid");
		
		ArrayList <Connection> conn = getServIncomConnections();
		for(Connection conne : conn) {
			if (conne != con) {
				Gson gs=new Gson();
				String msg = gs.toJson(jsonObject);
				conne.writeMsg(msg);
			}
		}
		ArrayList <Connection> connn = getServConnections();
		for(Connection connne : connn) {
			if (connne != con) {
				Gson gs=new Gson();
				String msg = gs.toJson(jsonObject);
				connne.writeMsg(msg);
			}
		}
		
		// Then handle lock requests
		if (userSec.containsKey(userName) == true) {
			// Send lock denied
			JSONObject obj1 = new JSONObject();
			obj1.put("command", "LOCK_DENIED");
			obj1.put("username", userName);
			obj1.put("secret", secret);
			obj1.put("server", id);
			obj1.put("serverid",serverid );
			
			//System.out.println("lock denied");
			
			ArrayList <Connection> conn1 = getServIncomConnections();
			for(Connection conne : conn1) {
					Gson gs=new Gson();
					String msg = gs.toJson(obj1);
					conne.writeMsg(msg);
				
			}
			ArrayList <Connection> connn1 = getServConnections();
			for(Connection connne : connn1) {
					Gson gs=new Gson();
					String msg = gs.toJson(obj1);
					connne.writeMsg(msg);
				
			}
			
		} else {
			// Send lock allowed
			JSONObject obj1 = new JSONObject();
			obj1.put("command", "LOCK_ALLOWED");
			obj1.put("username", userName);
			obj1.put("secret", secret);
			obj1.put("server", id);
			obj1.put("serverid", serverid);
			//System.out.println("lock allowed");
			if (userSysLogin.containsKey(userName) != true) {
				userSysLogin.put(userName, secret);
			}
			
			ArrayList <Connection> conn1 = getServIncomConnections();
			for(Connection conne : conn1) {
					Gson gs=new Gson();
					String msg = gs.toJson(obj1);
					conne.writeMsg(msg);
				
			}
			ArrayList <Connection> connn1 = getServConnections();
			for(Connection connne : connn1) {
					Gson gs=new Gson();
					String msg = gs.toJson(obj1);
					connne.writeMsg(msg);
				
			}
		}
		
	}
	
	public synchronized void lock_denied(JSONObject jsonObject, Connection con) {
		String userName = (String) jsonObject.get("username");
		String secret = (String) jsonObject.get("secret");
		
		ArrayList <Connection> conn1 = getServIncomConnections();
		for(Connection conne : conn1) {
			if (conne != con) {
				Gson gs=new Gson();
				String msg = gs.toJson(jsonObject);
				conne.writeMsg(msg);
			}
		}
		ArrayList <Connection> connn1 = getServConnections();
		for(Connection connne : connn1) {
			if (connne != con) {
				Gson gs=new Gson();
				String msg = gs.toJson(jsonObject);
				connne.writeMsg(msg);
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	public synchronized void lock_allowed(JSONObject jsonObject, Connection con) {
		
		String userName = (String) jsonObject.get("username");
		String secret = (String) jsonObject.get("secret");
		
		ArrayList <Connection> conn1 = getServIncomConnections();
		for(Connection conne : conn1) {
			if (conne != con) {
				Gson gs=new Gson();
				String msg = gs.toJson(jsonObject);
				conne.writeMsg(msg);
			}
		}
		ArrayList <Connection> connn1 = getServConnections();
		for(Connection connne : connn1) {
			if (connne != con) {
				Gson gs=new Gson();
				String msg = gs.toJson(jsonObject);
				connne.writeMsg(msg);
			}
		}
	}
	
	
	@SuppressWarnings("unchecked")
	public synchronized void sendLockRequest(JSONObject jsonObject, Connection con) {
		
		String userName = (String) jsonObject.get("username");
		String secret = (String) jsonObject.get("secret");
		
		JSONObject obj = new JSONObject();
		obj.put("command", "LOCK_REQUEST");
		obj.put("username", userName);
		obj.put("secret", secret);
		obj.put("serverid", id);
		
		// Broadcast to all other servers
		// Now broadcast to all servers.
				ArrayList <Connection> conn = getServIncomConnections();
				for(Connection conne : conn) {
					Gson gs=new Gson();
					String msg = gs.toJson(obj);
					conne.writeMsg(msg);
				}
				ArrayList <Connection> connn = getServConnections();
				for(Connection connne : connn) {
					Gson gs=new Gson();
					String msg = gs.toJson(obj);
					connne.writeMsg(msg);
				}
	}
	
	
	public boolean logout(JSONObject jsonObject, Connection con) {
		String userName;
		connectionClosed(con);
		con.closeCon();
		// Remove user from login hash table
		userName = userCon.get(con);
		userCon.remove(con);
		userLogin.remove(userName);
		// To decrement client load
		decrementLoad();
		return true;
	}
	

	@SuppressWarnings("unchecked")
	public void authenticate(JSONObject jsonobject, Connection con) {
		String command = (String) jsonobject.get("command");
		String secret = (String) jsonobject.get("secret");
		JSONObject obj = new JSONObject();
		int logout_flag = 1;
		
		if ((command.length() != 0) && (secret.compareTo(Settings.getSecret()) != 0) ) {
			obj.put("command", "AUTHENTICATE_FAIL");
			obj.put("info", "the supplied secret is incorrect: " + secret);
		} else if ((command.length() == 0) || (secret.length() == 0)) {
			obj.put("command", "INVALID_MESSAGE");
			obj.put("info", "the received message did not contain a command");
		} else {
			//success. No need to reply. Just store the secret locally
			serverSecret = secret;
			// Add connection to servIncomConnections
			addServIncomConnections(con);
			return;
		}
		
		Gson gs=new Gson();
		String msg = gs.toJson(obj);
		con.writeMsg(msg);
		if (logout_flag == 1) {
			connectionClosed(con);
			con.closeCon();
		}
	}	
	
	public void authenticate_fail(JSONObject jsonobject, Connection con) {
		String command = (String) jsonobject.get("command");
		String info = (String) jsonobject.get("info");
		if ((command != null) && (command.length() > 0)) {
			//System.out.println(command);
			if ((info != null) && (info.length() > 0)) {
				System.out.println(info);
			}
		}
		// remove connection from list
		removeServIncomConnections(con);
		
	}
	
	public static HashMap<String, Integer> getUserLogin() {
		return userLogin;
	}

	public static void setUserLogin(HashMap<String, Integer> userLogin) {
		ControlSolution.userLogin = userLogin;
	}

	public static HashMap<Connection, String> getUserCon() {
		return userCon;
	}

	public static void setUserCon(HashMap<Connection, String> userCon) {
		ControlSolution.userCon = userCon;
	}
	
}
