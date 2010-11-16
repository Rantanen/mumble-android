package org.pcgod.mumbleclient.service.model;

import android.os.Parcel;
import android.os.Parcelable;

public class Channel implements Parcelable {

	public static final Parcelable.Creator<Channel> CREATOR = new Creator<Channel>() {
		@Override
		public Channel[] newArray(int size) {
			return new Channel[size];
		}

		@Override
		public Channel createFromParcel(Parcel source) {
			return new Channel(source);
		}
	};

	public int id;
	public String name;
	public int userCount;

	/**
	 * Value signaling whether this channel has just been removed.
	 * Once this value is set the connection signals one last update for the
	 * channel which should result in the channel being removed from all the
	 * caches where it might be stored.
	 */
	public boolean removed = false;

	public Channel() {
	}

	public Channel(Parcel parcel) {
		readFromParcel(parcel);
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeInt(0); // Version

		dest.writeInt(id);
		dest.writeString(name);
		dest.writeInt(userCount);
	}

	private void readFromParcel(Parcel in) {
		in.readInt(); // Version

		id = in.readInt();
		name = in.readString();
		userCount = in.readInt();
	}

	@Override
	public int describeContents() {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public final boolean equals(final Object o) {
		if (!(o instanceof Channel)) {
			return false;
		}
		return id == ((Channel) o).id;
	}

	@Override
	public final int hashCode() {
		return id;
	}

	@Override
	public final String toString() {
		return "Channel [id=" + id + ", name=" + name + ", userCount=" +
				userCount + "]";
	}

}
