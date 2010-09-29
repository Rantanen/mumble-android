package org.pcgod.mumbleclient.app;

import java.util.ArrayList;
import java.util.List;

import org.pcgod.mumbleclient.service.MumbleClient;
import org.pcgod.mumbleclient.R;
import org.pcgod.mumbleclient.service.model.User;

import android.app.ListActivity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;

public class UserList extends ListActivity {
	private class UserAdapter extends ArrayAdapter<User> {
		public UserAdapter(final Context context, final List<User> users) {
			super(context, android.R.layout.simple_list_item_1, users);
		}

		@Override
		public final View getView(final int position, View v,
				final ViewGroup parent) {
			if (v == null) {
				final LayoutInflater inflater = (LayoutInflater) UserList.this
						.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				v = inflater.inflate(android.R.layout.simple_list_item_1, null);
			}
			final User u = getItem(position);
			final TextView tv = (TextView) v.findViewById(android.R.id.text1);
			tv.setText(u.name);
			if (u.session == ServerList.client.session) {
				tv.setTypeface(Typeface.DEFAULT_BOLD);
			} else {
				tv.setTypeface(Typeface.DEFAULT);
			}
			return tv;
		}
	}

	private class UserBroadcastReceiver extends BroadcastReceiver {
		@Override
		public final void onReceive(final Context ctx, final Intent i) {
			if (MumbleClient.INTENT_USER_LIST_UPDATE.equals(i.getAction())) {
				updateList();
			} else if (MumbleClient.INTENT_CURRENT_CHANNEL_CHANGED.equals(i
					.getAction())) {
				channelId = ServerList.client.currentChannel;
			}
			updateButtonVisibility();
		}
	}

	int channelId;
	Thread rt;
	private UserBroadcastReceiver bcReceiver;
	private ToggleButton speakButton;
	private Button joinButton;

	private static final int MENU_CHAT = Menu.FIRST;

	private final ArrayList<User> userList = new ArrayList<User>();
	private final OnClickListener joinButtonClickEvent = new OnClickListener() {
		public void onClick(final View v) {
			ServerList.client.joinChannel(channelId);
		}
	};

	private final OnClickListener speakButtonClickEvent = new OnClickListener() {
		public void onClick(final View v) {
			if (rt == null) {
				// start record
				// TODO check initialized
				rt = new Thread(new RecordThread(), "record");
				rt.start();
			} else {
				// stop record
				rt.interrupt();
				rt = null;
			}
		}
	};

	@Override
	public final boolean onCreateOptionsMenu(final Menu menu) {
		menu.add(0, MENU_CHAT, 0, "Chat").setIcon(
				android.R.drawable.ic_btn_speak_now);
		return true;
	}

	@Override
	public final boolean onMenuItemSelected(final int featureId,
			final MenuItem item) {
		switch (item.getItemId()) {
		case MENU_CHAT:
			final Intent i = new Intent(this, ChatActivity.class);
			startActivity(i);
			return true;
		default:
			return super.onMenuItemSelected(featureId, item);
		}
	}

	private void updateButtonVisibility() {
		if (channelId == ServerList.client.currentChannel) {
			joinButton.setVisibility(View.GONE);
			speakButton.setVisibility(View.VISIBLE);
		} else {
			joinButton.setVisibility(View.VISIBLE);
			speakButton.setVisibility(View.GONE);
		}
		speakButton.setEnabled(ServerList.client.canSpeak);
	}

	@Override
	protected final void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.user_list);
		setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

		if (ServerList.client == null) {
			finish();
			return;
		}

		if (!ServerList.client.isConnected()) {
			finish();
			return;
		}

		joinButton = (Button) findViewById(R.id.joinButton);
		speakButton = (ToggleButton) findViewById(R.id.speakButton);

		joinButton.setOnClickListener(joinButtonClickEvent);
		speakButton.setOnClickListener(speakButtonClickEvent);

		final Intent i = getIntent();
		channelId = (int) i.getLongExtra("channelId", -1);
		updateButtonVisibility();

		setListAdapter(new UserAdapter(this, userList));
		updateList();
	}

	@Override
	protected final void onPause() {
		super.onPause();

		// TODO keep recording state on all pages (and use the menu!)
		if (rt != null) {
			rt.interrupt();
			rt = null;
		}
		unregisterReceiver(bcReceiver);
	}

	@Override
	protected final void onResume() {
		super.onResume();

		speakButton.setChecked(false);
		final IntentFilter ifilter = new IntentFilter(
				MumbleClient.INTENT_USER_LIST_UPDATE);
		ifilter.addAction(MumbleClient.INTENT_CURRENT_CHANNEL_CHANGED);
		bcReceiver = new UserBroadcastReceiver();
		registerReceiver(bcReceiver, ifilter);
	}

	void updateList() {
		userList.clear();
		for (final User u : ServerList.client.userArray) {
			if (u.channel == channelId) {
				userList.add(u);
			}
		}
		((BaseAdapter) getListAdapter()).notifyDataSetChanged();
	}
}
