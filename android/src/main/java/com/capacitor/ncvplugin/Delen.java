package com.capacitor.ncvplugin;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Het deelmenu van Android (ACTION_SEND/ACTION_SEND_MULTIPLE). De Android-WebView heeft geen navigator.share; de app vult die
 * hiermee in. Bestanden komen in de cachemap (cache/delen/<tijd>, via de FileProvider van de app: `cache-path` in file_paths.xml),
 * per keer delen een eigen map: een ontvanger kan een bestand later lezen dan het menu sluit, en een volgende keer delen met
 * dezelfde bestandsnaam geeft een andere uri. Mappen ouder dan BEWAREN_MS gaan weg bij een volgende keer delen.
 */
class Delen {

    static final class Bestand {
        final String naam;
        final String type;
        final byte[] inhoud;

        Bestand(String naam, String type, byte[] inhoud) {
            this.naam = naam;
            this.type = type;
            this.inhoud = inhoud;
        }
    }

    private static final long BEWAREN_MS = 24L * 60 * 60 * 1000;

    private final Context context;

    Delen(Context context) {
        this.context = context.getApplicationContext();
    }

    static byte[] decodeer(String base64) {
        return Base64.decode(base64, Base64.DEFAULT);
    }

    /** Alleen de naam, zonder pad of tekens die een bestandsnaam breken */
    static String veiligeNaam(String naam) {
        String n = naam == null ? "" : naam.replaceAll(".*[/\\\\]", "").replaceAll("[\\x00-\\x1f:*?\"<>|]", "").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) n = "bestand";
        return n.length() > 100 ? n.substring(n.length() - 100) : n;
    }

    /** Het intent voor de keuzelijst; schrijft de bestanden weg (niet op de main thread aanroepen) */
    Intent maakIntent(String titel, String tekst, String url, List<Bestand> bestanden) throws IOException {
        String volledigeTekst = samenvoegen(tekst, url);
        List<Uri> uris = schrijf(bestanden);

        Intent intent;
        if (uris.size() > 1) {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
            intent.setType(gezamenlijkType(bestanden));
        } else if (uris.size() == 1) {
            intent = new Intent(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            intent.setType(bestanden.get(0).type);
        } else {
            intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
        }
        if (!uris.isEmpty()) {
            //zonder ClipData krijgt de ontvanger bij ACTION_SEND_MULTIPLE geen leesrecht, en het menu geen voorbeeld
            ClipData clip = ClipData.newRawUri(titel, uris.get(0));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
            intent.setClipData(clip);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        if (volledigeTekst != null) intent.putExtra(Intent.EXTRA_TEXT, volledigeTekst);
        if (titel != null && !titel.isEmpty()) {
            intent.putExtra(Intent.EXTRA_SUBJECT, titel);
            intent.putExtra(Intent.EXTRA_TITLE, titel);
        }

        Intent kiezer = Intent.createChooser(intent, titel == null || titel.isEmpty() ? null : titel);
        kiezer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return kiezer;
    }

    /** Zoals Chrome op Android: tekst en url samen in EXTRA_TEXT */
    static String samenvoegen(String tekst, String url) {
        boolean heeftTekst = tekst != null && !tekst.isEmpty();
        boolean heeftUrl = url != null && !url.isEmpty();
        if (heeftTekst && heeftUrl) return tekst.contains(url) ? tekst : tekst + "\n" + url;
        if (heeftTekst) return tekst;
        if (heeftUrl) return url;
        return null;
    }

    private static String gezamenlijkType(List<Bestand> bestanden) {
        String type = bestanden.get(0).type;
        String hoofd = type.contains("/") ? type.substring(0, type.indexOf('/')) : type;
        boolean zelfde = true, zelfdeHoofd = true;
        for (Bestand b : bestanden) {
            if (!b.type.equals(type)) zelfde = false;
            if (!b.type.startsWith(hoofd + "/")) zelfdeHoofd = false;
        }
        return zelfde ? type : zelfdeHoofd ? hoofd + "/*" : "*/*";
    }

    private List<Uri> schrijf(List<Bestand> bestanden) throws IOException {
        File basis = new File(context.getCacheDir(), "delen");
        long nu = System.currentTimeMillis();
        File[] oud = basis.listFiles();
        if (oud != null) for (File f : oud) if (nu - f.lastModified() > BEWAREN_MS) verwijder(f);
        List<Uri> uris = new ArrayList<>();
        if (bestanden.isEmpty()) return uris;
        File map = new File(basis, nu + "-" + java.util.UUID.randomUUID());
        String autoriteit = context.getPackageName() + ".fileprovider";
        for (int i = 0; i < bestanden.size(); i++) {
            //een eigen submap per bestand: twee bestanden met dezelfde naam overschrijven elkaar niet, en de naam blijft zoals hij was
            File sub = new File(map, String.valueOf(i));
            if (!sub.isDirectory() && !sub.mkdirs()) throw new IOException("Kan de map voor delen niet maken");
            File f = new File(sub, bestanden.get(i).naam);
            try (FileOutputStream uit = new FileOutputStream(f)) {
                uit.write(bestanden.get(i).inhoud);
            }
            uris.add(FileProvider.getUriForFile(context, autoriteit, f));
        }
        return uris;
    }

    private static void verwijder(File f) {
        File[] kinderen = f.listFiles();
        if (kinderen != null) for (File k : kinderen) verwijder(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
