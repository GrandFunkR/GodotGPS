
package com.android.godot;

import java.util.List;
import java.util.ArrayList;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.games.Games;
import com.google.android.gms.games.Player;
import com.google.android.gms.games.GamesActivityResultCodes;
import com.google.android.gms.plus.Plus;
import com.google.android.gms.plus.model.people.Person;
import com.google.android.gms.games.multiplayer.realtime.RoomConfig;
import com.google.android.gms.games.multiplayer.realtime.Room;
import com.google.android.gms.games.multiplayer.realtime.RoomUpdateListener;
import com.google.android.gms.games.multiplayer.realtime.RealTimeMessageReceivedListener;
import com.google.android.gms.games.multiplayer.realtime.RealTimeMessage;
import com.google.android.gms.games.multiplayer.realtime.RoomStatusUpdateListener;
import com.google.android.gms.games.GamesStatusCodes;
import com.google.android.gms.games.multiplayer.Invitations;
import com.google.android.gms.games.multiplayer.Invitation;
import com.google.android.gms.games.multiplayer.Multiplayer;
import com.google.android.gms.games.multiplayer.OnInvitationReceivedListener;
import com.google.android.gms.games.multiplayer.Participant;

import android.os.Bundle;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.util.Log;
import android.app.Activity;
import android.view.WindowManager;
import android.view.Window;
import android.widget.Toast;


public class GodotGPS extends Godot.SingletonBase implements	  RoomUpdateListener
																, RealTimeMessageReceivedListener
																, RoomStatusUpdateListener
																, OnInvitationReceivedListener
																, GoogleApiClient.ConnectionCallbacks
																, GoogleApiClient.OnConnectionFailedListener
{
	private static final String TAG = "godot";
	private static final int	REQUEST_RESOLVE_ERROR	= 1001;
	private static final int	REQUEST_LEADERBOARD		= 1002;
	private static final int	REQUEST_ACHIEVEMENTS	= 1003;
	private static final int	RC_SELECT_PLAYERS		= 10000;
	private static final int	RC_WAITING_ROOM 		= 10002;
	private static final int 	RC_INVITATION_INBOX 	= 10001;
	private static final int 	RC_SIGN_IN 				= 9001;

	private Activity		activity			= null;
	private GoogleApiClient	mGoogleApiClient	= null;
	private boolean			isResolvingError	= false;
	private String			lbId				= null;
	private int				lbScore				= 0;
	private String			acId				= null;
	private int				acVal				= 0;
	private int				instanceId			= 0;
	private int				m_min				= 0;
	private int				m_max				= 0;
	
	// My participant ID in the currently active game
    String mMyId = null;
	
	// Room ID where the currently active game is taking place; null if we're
    // not playing.
    private String mRoomId = null;
	
	// The participants in the currently active game
    ArrayList<Participant> mParticipants = null;
	
	// If non-null, this is the id of the invitation we received via the
    // invitation listener
    String mIncomingInvitationId = null;
	
	
	//private methods
	private void disconnect()
	{
		leaveRoom();
		Games.signOut(mGoogleApiClient);
		Plus.AccountApi.clearDefaultAccount(mGoogleApiClient);
		mGoogleApiClient.disconnect();
	}
	
	private RoomConfig.Builder makeBasicRoomConfigBuilder() {
		return RoomConfig.builder(this)
				.setMessageReceivedListener(this)
				.setRoomStatusUpdateListener(this)
				.setSocketCommunicationEnabled(true);
	}
	
	// Handle the result of the "Select players UI" we launched when the user clicked the
    // "Invite friends" button. We react by creating a room with those players.
    private void handleSelectPlayersResult(int response, Intent data) {
        if (response != Activity.RESULT_OK) {
			Log.d(TAG, "*** select players UI cancelled, " + response);
			GodotLib.calldeferred(instanceId, "_on_selected_player_ui_cancelled", new Object[]{});
            return;
        }

        Log.d(TAG, "Select players UI succeeded.");

        // get the invitee list
        final ArrayList<String> invitees = data.getStringArrayListExtra(Games.EXTRA_PLAYER_IDS);
        Log.d(TAG, "Invitee count: " + invitees.size());

        // get the automatch criteria
        Bundle autoMatchCriteria = null;
        int minAutoMatchPlayers = data.getIntExtra(Multiplayer.EXTRA_MIN_AUTOMATCH_PLAYERS, 0);
        int maxAutoMatchPlayers = data.getIntExtra(Multiplayer.EXTRA_MAX_AUTOMATCH_PLAYERS, 0);
        if (minAutoMatchPlayers > 0 || maxAutoMatchPlayers > 0) {
            autoMatchCriteria = RoomConfig.createAutoMatchCriteria(
                    minAutoMatchPlayers, maxAutoMatchPlayers, 0);
            Log.d(TAG, "Automatch criteria: " + autoMatchCriteria);
        }

        // create the room
        Log.d(TAG, "Creating room...");
        RoomConfig.Builder rtmConfigBuilder = RoomConfig.builder(this);
        rtmConfigBuilder.addPlayersToInvite(invitees);
        rtmConfigBuilder.setMessageReceivedListener(this);
        rtmConfigBuilder.setRoomStatusUpdateListener(this);
        if (autoMatchCriteria != null) {
            rtmConfigBuilder.setAutoMatchCriteria(autoMatchCriteria);
        }

        Games.RealTimeMultiplayer.create(mGoogleApiClient, rtmConfigBuilder.build());
        Log.d(TAG, "Room created, waiting for it to be ready...");
    }
	
    private void handleInvitationInboxResult(int response, Intent data) {
		// Handle the result of the invitation inbox UI, where the player can pick an invitation
		// to accept. We react by accepting the selected invitation, if any.
        if (response != Activity.RESULT_OK) {
            Log.d(TAG, "*** invitation inbox UI cancelled, " + response);
            GodotLib.calldeferred(instanceId, "_on_invitation_cancelled", new Object[]{});
            return;
        }

        Log.d(TAG, "Invitation inbox UI succeeded.");
        Invitation inv = data.getExtras().getParcelable(Multiplayer.EXTRA_INVITATION);
		

        // accept invitation
        acceptInviteToRoom(inv.getInvitationId());
		
		GodotLib.calldeferred(instanceId, "_on_invitation_succeeded", new Object[]{});
    }
	
	
    private void acceptInviteToRoom(String invId) {
        // Accept the given invitation.
        Log.d(TAG, "Accepting invitation: " + invId);
        RoomConfig.Builder roomConfigBuilder = RoomConfig.builder(this);
        roomConfigBuilder.setInvitationIdToAccept(invId)
                .setMessageReceivedListener(this)
                .setRoomStatusUpdateListener(this);
        //switchToScreen(R.id.screen_wait);
        //keepScreenOn();
        //resetGameVars();
        Games.RealTimeMultiplayer.join(mGoogleApiClient, roomConfigBuilder.build());
    }
	
	
    private void leaveRoom() {
		// Leave the room.
        Log.d(TAG, "Leaving room.");
        //mSecondsLeft = 0;
        //stopKeepingScreenOn();
        if (mRoomId != null) {
            Games.RealTimeMultiplayer.leave(mGoogleApiClient, this, mRoomId);
			GodotLib.calldeferred(instanceId, "_on_leave_room", new Object[]{mRoomId});
            mRoomId = null;
            //switchToScreen(R.id.screen_wait);
        } else {
            //switchToMainScreen();
        }
    }
	
	private void updateRoom(Room room) {
        if (room != null) {
            mParticipants = room.getParticipants();
        }
        if (mParticipants != null) {
			GodotLib.calldeferred(instanceId, "_on_updatePeerScoresDisplay", new Object[]{});
        }
    }
	
	// Show the waiting room UI to track the progress of other players as they enter the
    // room and get connected.
    private void showWaitingRoom(Room room) {
        // minimum number of players required for our game
        // For simplicity, we require everyone to join the game before we start it
        // (this is signaled by Integer.MAX_VALUE).
        final int MIN_PLAYERS = Integer.MAX_VALUE;
        Intent i = Games.RealTimeMultiplayer.getWaitingRoomIntent(mGoogleApiClient, room, MIN_PLAYERS);

        // show waiting room UI
        activity.startActivityForResult(i, RC_WAITING_ROOM);
    }
	
	// Show error message about game being cancelled and return to main screen.
    private void showGameError() {
		Log.d(TAG, "GooglePlayService: showGameError");
        GodotLib.calldeferred(instanceId, "_on_show_game_error", new Object[]{});
    }
	
	//public methods
	public void init()
	{
		Log.d(TAG, "GooglePlayService: init");

		// Create the Google Api Client with access to Plus and Games
		mGoogleApiClient = new GoogleApiClient.Builder(activity)
			.addConnectionCallbacks(this)
			.addOnConnectionFailedListener(this)
			.addApi(Plus.API).addScope(Plus.SCOPE_PLUS_LOGIN)
			.addApi(Games.API).addScope(Games.SCOPE_GAMES)
			.build();

		isResolvingError	= false;
		mGoogleApiClient.connect();
	}
	
	public void signin()
	{
		Log.d(TAG, "GooglePlayService: signin");
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				if (!mGoogleApiClient.isConnecting())
				{
					isResolvingError	= false;
					mGoogleApiClient.connect();
				}
			}
		});
	}
	
	public void signout()
	{
		Log.d(TAG, "GooglePlayService: signout");
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				if (mGoogleApiClient.isConnected())
				{
					disconnect();
				}
			}
		});
	}
	
	public int getStatus()
	{
		if(mGoogleApiClient!=null)
		{
			if(mGoogleApiClient.isConnecting())	return 1;
			if(mGoogleApiClient.isConnected())	return 2;
		}
		return 0;
	}
	
	public void getProfileInfo()
	{
		Log.d(TAG, "GooglePlayService: printInfo");
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				try
				{
					if (Plus.PeopleApi.getCurrentPerson(mGoogleApiClient) != null)
					{
						Person	person	= Plus.PeopleApi.getCurrentPerson(mGoogleApiClient);
						Log.d(TAG, "GooglePlayService: name = " + person.getDisplayName());
						Log.d(TAG, "GooglePlayService: photo = " + person.getImage().getUrl());
						Log.d(TAG, "GooglePlayService: g+ profile = " + person.getUrl());
						Log.d(TAG, "GooglePlayService: email = " + Plus.AccountApi.getAccountName(mGoogleApiClient));
						
						GodotLib.calldeferred(instanceId, "_on_getCurrentPerson", new Object[]{"OK", person.getDisplayName(), person.getImage().getUrl(), person.getUrl(),  Plus.AccountApi.getAccountName(mGoogleApiClient)});
						
						
					}
					else
					{
						Log.d(TAG, "GooglePlayService: info is null");
						
						GodotLib.calldeferred(instanceId, "_on_getCurrentPerson", new Object[]{"KO", null, null, null, null});
						
					}
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}
			}
		});
	}
	
		public void lbSubmit(String id, int score)
	{
		Log.d(TAG, "GooglePlayService: lbSubmit");
		lbId		= id;
		lbScore	= score;
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				if (mGoogleApiClient.isConnected())
				{
					Games.Leaderboards.submitScore(mGoogleApiClient, lbId, lbScore);
				}
			}
		});
	}
	
	public void lbShow(String id)
	{
		Log.d(TAG, "GooglePlayService: lbShow");
		lbId		= id;
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				if (mGoogleApiClient.isConnected())
				{
					activity.startActivityForResult(Games.Leaderboards.getLeaderboardIntent(
						mGoogleApiClient, lbId), REQUEST_LEADERBOARD);
				}
			}
		});
	}

	
	public void acUnlock(String id)
	{
		Log.d(TAG, "GooglePlayService: acUnlock");
		acId		= id;
		
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				if (mGoogleApiClient.isConnected())
				{
					Games.Achievements.unlock(mGoogleApiClient, acId);
				}
			}
		});
	}
	public void acIncrement(String id, int val)
	{
		Log.d(TAG, "GooglePlayService: acUnlock");
		
		acId		= id;
		acVal		= val;
		
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				if (mGoogleApiClient.isConnected())
				{
					Games.Achievements.increment(mGoogleApiClient, acId, acVal);
				}
			}
		});
	}
	public void acDisplay()
	{
		Log.d(TAG, "GooglePlayService: lbShow");
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				if (mGoogleApiClient.isConnected())
				{
					activity.startActivityForResult(Games.Achievements.getAchievementsIntent(
						mGoogleApiClient), REQUEST_ACHIEVEMENTS);
				}
			}
		});
	}
	
	
	public void setInstanceID(int new_instanceId)
	{
		Log.d(TAG, "GooglePlayService: setInstanceID");
		instanceId = new_instanceId;
	}
	
	public void startQuickGame() {
		Log.d(TAG, "GooglePlayService: startQuickGame");
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				if (mGoogleApiClient.isConnected())
				{
					// auto-match criteria to invite one random automatch opponent.
					// You can also specify more opponents (up to 3).
					Bundle am = RoomConfig.createAutoMatchCriteria(1, 1, 0);

					// build the room config:
					RoomConfig.Builder roomConfigBuilder = makeBasicRoomConfigBuilder();
					
					roomConfigBuilder.setAutoMatchCriteria(am);
					RoomConfig roomConfig = roomConfigBuilder.build();
				
					// create room:
					Games.RealTimeMultiplayer.create(mGoogleApiClient, roomConfig);
				
					// prevent screen from sleeping during handshake
					//getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				
					// go to game screen
					//Test
					GodotLib.calldeferred(instanceId, "test", new Object[]{1,2});
				}
			}
		});
	}
	
	public void selectOpponents(int min, int max) {
		Log.d(TAG, "GooglePlayService: selectOpponents");
		
		m_min = min;
		m_max = max;
		
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				if (mGoogleApiClient.isConnected())
				{
					// launch the player selection screen
					// minimum: min other player; maximum: max other players
					Intent intent = Games.RealTimeMultiplayer.getSelectOpponentsIntent(mGoogleApiClient, m_min, m_max);
					activity.startActivityForResult(intent, RC_SELECT_PLAYERS);
				}
			}
		});
	}
	
	
	public void seeInvitations() {
		Log.d(TAG, "GooglePlayService: seeInvitations");
		
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				if (mGoogleApiClient.isConnected())
				{
					// show list of pending invitations
					Intent intent = Games.Invitations.getInvitationInboxIntent(mGoogleApiClient);
					//switchToScreen(R.id.screen_wait);
					activity.startActivityForResult(intent, RC_INVITATION_INBOX);
				}
			}
		});
	}
	

	//Overrides
	
	@Override
    public void onInvitationReceived(Invitation invitation) {
		// Called when we get an invitation to play a game. We react by showing that to the user.
    
	    // We got an invitation to play a game! So, store it in
        // mIncomingInvitationId
        // and show the popup on the screen.
		Log.d(TAG, "GooglePlayService: onInvitationReceived");
        mIncomingInvitationId = invitation.getInvitationId();
		
		GodotLib.calldeferred(instanceId, "_on_invitation_recieved", new Object[]{invitation.getInviter().getDisplayName()});
    }

    @Override
    public void onInvitationRemoved(String invitationId) {
		Log.d(TAG, "GooglePlayService: onInvitationRemoved");
        if (mIncomingInvitationId.equals(invitationId)) {
            mIncomingInvitationId = null;
            GodotLib.calldeferred(instanceId, "_on_invitation_emoved", new Object[]{});
        }
    }
	
	@Override protected void onMainActivityResult(int requestCode, int responseCode, Intent intent)
	{
		switch(requestCode)
		{
		case REQUEST_RESOLVE_ERROR:
			if (responseCode != Activity.RESULT_OK)
			{
				Log.d(TAG, "GooglePlayService: onMainActivityResult, REQUEST_RESOLVE_ERROR = " + responseCode);
			}
			isResolvingError	= true;
			if (!mGoogleApiClient.isConnecting() && !mGoogleApiClient.isConnected())
			{
				mGoogleApiClient.connect();
			}
			break;
		case REQUEST_LEADERBOARD:
			Log.d(TAG, "GooglePlayService: onMainActivityResult, REQUEST_LEADERBOARD = " + responseCode);
			if(responseCode == GamesActivityResultCodes.RESULT_RECONNECT_REQUIRED)
			{
				disconnect();
			}
			break;
		case RC_SELECT_PLAYERS:
			// we got the result from the "select players" UI -- ready to create the room
			Log.d(TAG, "GooglePlayService: onMainActivityResult, RC_SELECT_PLAYERS");
			handleSelectPlayersResult(responseCode, intent);
			break;
		case RC_INVITATION_INBOX:
			// we got the result from the "select invitation" UI (invitation inbox). We're
			// ready to accept the selected invitation:
			Log.d(TAG, "GooglePlayService: onMainActivityResult, RC_INVITATION_INBOX");
			handleInvitationInboxResult(responseCode, intent);
			break;
		case RC_WAITING_ROOM:
			Log.d(TAG, "GooglePlayService: onMainActivityResult, RC_WAITING_ROOM");
			// we got the result from the "waiting room" UI.
			if (responseCode == Activity.RESULT_OK) {
				// ready to start playing
				Log.d(TAG, "Starting game (waiting room returned OK).");
				GodotLib.calldeferred(instanceId, "_on_game_start", new Object[]{});
				
			} else if (responseCode == GamesActivityResultCodes.RESULT_LEFT_ROOM) {
				// player indicated that they want to leave the room
				leaveRoom();
			} else if (responseCode == Activity.RESULT_CANCELED) {
				// Dialog was cancelled (user pressed back key, for instance). In our game,
				// this means leaving the room too. In more elaborate games, this could mean
				// something else (like minimizing the waiting room UI).
				leaveRoom();
			}
			break;
		case RC_SIGN_IN:
			Log.d(TAG, "GooglePlayService: onMainActivityResult, RC_SELECT_PLAYERS, responseCode="
				+ responseCode + ", intent=" + intent);
			/*mSignInClicked = false;
			mResolvingConnectionFailure = false;*/
			/*if (responseCode == RESULT_OK) {
			  mGoogleApiClient.connect();
			} else {
			  BaseGameUtils.showActivityResultError(this,requestCode,responseCode, R.string.signin_other_error);
			}*/
			break;
		}
	}
	
	
	

	/*public void revoke()
	{
		Log.d(TAG, "GooglePlayService: revoke");
		activity.runOnUiThread(new Runnable()
		{
			@Override public void run()
			{
				if (client.isConnected())
				{
					Plus.AccountApi.clearDefaultAccount(client);
					Plus.AccountApi.revokeAccessAndDisconnect(client)
						.setResultCallback(new ResultCallback<Status>()
						{
							@Override public void onResult(Status arg0)
							{
								Log.d(TAG, "GooglePlayService: revoked");
								//client.connect();
							}
						});
				}
			}
		});
	}*/
	
	/**/
	

	

	@Override
	public void onJoinedRoom(int statusCode, Room room) {
		//Called when the client attempts to join a real-time room.
		Log.d(TAG, "GooglePlayService: onJoinedRoom statusCode:" + statusCode);
		
		if (statusCode != GamesStatusCodes.STATUS_OK) {
			// display error
			Log.d(TAG, "GooglePlayService: onJoinedRoom Errore");
			showGameError();
			return;
		}
	 
		 // show the waiting room UI
        showWaitingRoom(room);
		// get waiting room intent
		//Intent i = Games.RealTimeMultiplayer.getWaitingRoomIntent(mGoogleApiClient, room, Integer.MAX_VALUE);
		//activity.startActivityForResult(i, RC_WAITING_ROOM);
	}
	
	@Override
	public void onLeftRoom(int statusCode, String roomId){
		//Called when the client attempts to leaves the real-time room.
		Log.d(TAG, "GooglePlayService: onLeftRoom");
		
		GodotLib.calldeferred(instanceId, "_on_left_room", new Object[]{statusCode, roomId});
	}

	@Override
	public void onRoomConnected(int statusCode, Room room){
		//Called when all the participants in a real-time room are fully connected.
		Log.d(TAG, "GooglePlayService: onRoomConnected");
		if (statusCode != GamesStatusCodes.STATUS_OK) {
            Log.e(TAG, "*** Error: onRoomConnected, status " + statusCode);
            showGameError();
            return;
        }
        updateRoom(room);
	}
	
	@Override
	public void onRoomCreated(int statusCode, Room room){
		//Called when the client attempts to create a real-time room.
		Log.d(TAG, "GooglePlayService: onRoomCreated status:" + statusCode);
		
		if (statusCode != GamesStatusCodes.STATUS_OK) {
			// let screen go to sleep
			//getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			
			Log.d(TAG, "GooglePlayService: onRoomCreated status: KO");
			// show error message, return to main screen.
			Toast t = Toast.makeText(activity.getApplicationContext(), "There was an error creating the room.", 5);
			t.show();
			showGameError();
			return;
		} 
		else if (statusCode == GamesStatusCodes.STATUS_OK) {
			Log.d(TAG, "GooglePlayService: onRoomCreated status: OK");
			// get waiting room intent
			showWaitingRoom(room);
			//Intent i = Games.RealTimeMultiplayer.getWaitingRoomIntent(mGoogleApiClient, room, Integer.MAX_VALUE);
			//activity.startActivityForResult(i, RC_WAITING_ROOM);   
		}
	}

	@Override
	public void onRealTimeMessageReceived(RealTimeMessage message){
		//Called to notify the client that a reliable or unreliable message was received for a room.
		Log.d(TAG, "GooglePlayService: onRealTimeMessageReceived");
	}

	@Override
	public void onConnectedToRoom(Room room){
		//Called when the client is connected to the connected set in a room.
		Log.d(TAG, "GooglePlayService: onConnectedToRoom");
		
		// get room ID, participants and my ID:
        mRoomId = room.getRoomId();
        mParticipants = room.getParticipants();
        mMyId = room.getParticipantId(Games.Players.getCurrentPlayerId(mGoogleApiClient));

		GodotLib.calldeferred(instanceId, "_on_connected_to_room", new Object[]{mRoomId, mParticipants, mMyId});
        // print out the list of participants (for debug purposes)
        Log.d(TAG, "Room ID: " + mRoomId);
        Log.d(TAG, "My ID " + mMyId);
        Log.d(TAG, "<< CONNECTED TO ROOM>>");
	}

	@Override
	public void onDisconnectedFromRoom(Room room){
		//Called when the client is disconnected from the connected set in a room.
		Log.d(TAG, "GooglePlayService: onDisconnectedFromRoom");
		GodotLib.calldeferred(instanceId, "_on_disconnected_from_room", new Object[]{room});
		showGameError();
	}
	@Override
	public void onP2PConnected(String participantId){
		//Called when the client is successfully connected to a peer participant.
		Log.d(TAG, "GooglePlayService: onP2PConnected");
	}
	@Override
	public void onP2PDisconnected(String participantId){
		//Called when client gets disconnected from a peer participant.
		Log.d(TAG, "GooglePlayService: onP2PDisconnected");
	}
	@Override
	public void onPeerDeclined(Room room, List<String> participantIds){
		//Called when one or more peers decline the invitation to a room.
		Log.d(TAG, "GooglePlayService: onPeerDeclined");
		updateRoom(room);
	}
	@Override
	public void onPeerInvitedToRoom(Room room, List<String> participantIds){
		//Called when one or more peers are invited to a room.
		Log.d(TAG, "GooglePlayService: onPeerInvitedToRoom");
		updateRoom(room);
	}
	@Override
	public void onPeerJoined(Room room, List<String> participantIds){
		//Called when one or more peer participants join a room.
		Log.d(TAG, "GooglePlayService: onPeerJoined");
		updateRoom(room);
	}
	@Override
	public void onPeerLeft(Room room, List<String> participantIds){
		//Called when one or more peer participant leave a room.
		Log.d(TAG, "GooglePlayService: onPeerLeft");
		updateRoom(room);
	}
	@Override
	public void onPeersConnected(Room room, List<String> participantIds){
		//Called when one or more peer participants are connected to a room.
		Log.d(TAG, "GooglePlayService: onRealTimeMessageReceived");
		updateRoom(room);
	}
	@Override
	public void onPeersDisconnected(Room room, List<String> participantIds){
		//Called when one or more peer participants are disconnected from a room.
		Log.d(TAG, "GooglePlayService: onPeersDisconnected");
		updateRoom(room);
	}
	@Override
	public void onRoomAutoMatching(Room room){
		//Called when the server has started the process of auto-matching.
		Log.d(TAG, "GooglePlayService: onRoomAutoMatching");
		updateRoom(room);
	}
	@Override
	public void onRoomConnecting(Room room){
		//Called when one or more participants have joined the room and have started the process of establishing peer connections.
		Log.d(TAG, "GooglePlayService: onRoomConnecting");
		updateRoom(room);
	}

	@Override public void onConnected(Bundle connectionHint)
	{
		Log.d(TAG, "GooglePlayService: onConnected");
		
		
		GodotLib.calldeferred(instanceId, "_on_connected", new Object[]{});
		
		// register listener so we are notified if we receive an invitation to play
		// while we are in the game
		Games.Invitations.registerInvitationListener(mGoogleApiClient, this);

		if (connectionHint != null) {
			Log.d(TAG, "GooglePlayService: onConnected + invitation");
			Invitation inv =
				connectionHint.getParcelable(Multiplayer.EXTRA_INVITATION);
			
			if (inv != null) {
				// accept invitation
				RoomConfig.Builder roomConfigBuilder = makeBasicRoomConfigBuilder();
				roomConfigBuilder.setInvitationIdToAccept(inv.getInvitationId());
				Games.RealTimeMultiplayer.join(mGoogleApiClient, roomConfigBuilder.build());
	
				// prevent screen from sleeping during handshake
				//getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
	
				// go to game screen
			}
		}
		
	}
	
	@Override public void onConnectionSuspended(int i)
	{
		Log.d(TAG, "GooglePlayService: onConnectionSuspended");
		mGoogleApiClient.connect();
	}
	@Override public void onConnectionFailed(ConnectionResult result)
	{
		if(isResolvingError)
		{
			Log.d(TAG, "GooglePlayService: onConnectionFailed->" + result.toString());
			return;
		}
		else if (result.hasResolution())
		{
			try
			{
				isResolvingError	= true;
				result.startResolutionForResult(activity, REQUEST_RESOLVE_ERROR);
			}
			catch (SendIntentException e)
			{
				// There was an error with the resolution intent. Try again.
				Log.d(TAG, "GooglePlayService: onConnectionFailed, try again");
				mGoogleApiClient.connect();
			}
		}
		else
		{
			// Show dialog using GooglePlayServicesUtil.getErrorDialog()
			//showErrorDialog(result.getErrorCode());
			Log.d(TAG, "GooglePlayService: onConnectionFailed->" + result.toString());
			
			Toast t = Toast.makeText(activity.getApplicationContext(), "Failed to connect to Google Play Services.", 15);
			t.show();
			
			isResolvingError	= true;
			GooglePlayServicesUtil.getErrorDialog(result.getErrorCode(), activity, 0).show();
			GodotLib.calldeferred(instanceId, "on_connection_failed", new Object[]{result.toString()});
		}
	}
	
	static public Godot.SingletonBase initialize(Activity p_activity)
	{
		return new GodotGPS(p_activity);
	}
	
	public GodotGPS(Activity p_activity)
	{
		registerClass("GooglePlayService", new String[]
		{
			"init", "signin", "signout", "getStatus", /*"revoke",*/ "getProfileInfo", "lbSubmit", "lbShow", "acUnlock", "acIncrement", "acDisplay", "setInstanceID", "startQuickGame", "selectOpponents", "seeInvitations"
		});
		activity	= p_activity;
	}
}

