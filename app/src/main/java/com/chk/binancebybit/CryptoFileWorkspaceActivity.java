package com.chk.binancebybit;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.content.ClipData;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Android SAF document workspace. Only documents explicitly chosen by the user
 * can be read/exported. No MANAGE_EXTERNAL_STORAGE or blanket filesystem access.
 */
public final class CryptoFileWorkspaceActivity extends Activity {
    private static final int PICK = 7201, EXPORT = 7202;
    private Uri selected;
    private TextView details;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10,12,15));
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p=(int)(20*getResources().getDisplayMetrics().density);
        root.setPadding(p,p,p,p);
        scroll.addView(root);
        TextView title=label("Fichiers CHK Crypto",24);root.addView(title);
        root.addView(label("PDF, MP3, MP4, images, archives, documents et autres formats. Android te demande de choisir les fichiers : aucune permission générale de lire ton téléphone.",14));
        details=label("Aucun fichier sélectionné.",14);root.addView(details);
        Button pick=button("Choisir un fichier");
        pick.setOnClickListener(v->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i,PICK);
        });
        root.addView(pick);
        Button export=button("Exporter une copie du fichier sélectionné");
        export.setOnClickListener(v->{
            if(selected==null){Toast.makeText(this,"Choisis d'abord un fichier",Toast.LENGTH_SHORT).show();return;}
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(getContentResolver().getType(selected)==null?"application/octet-stream":getContentResolver().getType(selected));
            i.putExtra(Intent.EXTRA_TITLE,name(selected));
            startActivityForResult(i,EXPORT);
        });
        root.addView(export);
        Button share=button("Partager le fichier sélectionné");
        share.setOnClickListener(v->{
            if(selected==null){Toast.makeText(this,"Choisis d'abord un fichier",Toast.LENGTH_SHORT).show();return;}
            Intent i=new Intent(Intent.ACTION_SEND);
            i.setType(getContentResolver().getType(selected)==null?"*/*":getContentResolver().getType(selected));
            i.putExtra(Intent.EXTRA_STREAM,selected);
            i.setClipData(ClipData.newRawUri("CHK Crypto",selected));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,"Partager depuis CHK Crypto"));
        });
        root.addView(share);
        root.addView(label("Les fichiers choisis restent sur ton téléphone. Aucun MP3, MP4, PDF ni document n'est envoyé automatiquement au serveur MCP. L'exportation exige ton choix de destination.",13));
        setContentView(scroll);
    }
    private TextView label(String value,int size) {
        TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(size);
        t.setPadding(0,16,0,16);return t;
    }
    private Button button(String value) {
        Button b=new Button(this);b.setText(value);b.setAllCaps(false);return b;
    }
    private String name(Uri uri) {
        try(Cursor c=getContentResolver().query(uri,null,null,null,null)){
            if(c!=null&&c.moveToFirst()){
                int ix=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(ix>=0)return c.getString(ix);
            }
        }catch(Exception ignored){}
        return "document";
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(request==PICK){
            selected=uri;
            try{
                int flags=data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if(flags!=0)getContentResolver().takePersistableUriPermission(uri,flags);
            }catch(Exception ignored){}
            details.setText("Fichier : "+name(uri)+"\nType : "+getContentResolver().getType(uri)+"\nURI protégée par Android : "+uri.getScheme());
        }else if(request==EXPORT&&selected!=null){
            try(InputStream in=getContentResolver().openInputStream(selected);
                OutputStream out=getContentResolver().openOutputStream(uri,"w")){
                if(in==null||out==null)throw new IllegalStateException("Fichier inaccessible");
                byte[] buf=new byte[65536];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
                out.flush();
                Toast.makeText(this,"Copie exportée",Toast.LENGTH_LONG).show();
            }catch(Exception e){Toast.makeText(this,"Export impossible : "+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();}
        }
    }
}
