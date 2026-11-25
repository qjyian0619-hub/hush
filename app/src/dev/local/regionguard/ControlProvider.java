package dev.local.regionguard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Synchronous root/shell-only control, independent of background broadcast scheduling. */
public final class ControlProvider extends ContentProvider {
    @Override public boolean onCreate() {return true;}
    @Override public Bundle call(String method,String arg,Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != 0 && uid != 1000 && uid != 2000 && uid != Process.myUid()) throw new SecurityException("Root/shell control only");
        try {
            Intent intent = new Intent();
            if (extras != null) intent.putExtras(extras);
            intent.putExtra("operation",method);
            ControlReceiver.execute(getContext(),intent);
            Bundle result = new Bundle(); result.putBoolean("ok",true); return result;
        } catch (Exception error) {throw new IllegalStateException("Region Guard control failed",error);}
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String sort) {throw new UnsupportedOperationException();}
    @Override public String getType(Uri uri) {return null;}
    @Override public Uri insert(Uri uri,ContentValues values) {throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] arguments) {throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] arguments) {throw new UnsupportedOperationException();}
}
