using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Microsoft.Win32.SafeHandles;
using Pactum.Nucleo;

namespace Pactum.Sistema;

/// <summary>
/// (0.18, contratto v4.0) La firma Authenticode di un eseguibile: il soggetto (CN) del certificato con cui
/// è firmato, <b>solo se la firma è valida</b> (Windows la verifica con <c>WinVerifyTrust</c>: il file non è
/// stato cambiato dopo la firma e il certificato è di una catena fidata), altrimenti <c>null</c>. Serve allo
/// Studio: una voce <c>exe:</c> con una <c>firma</c> conta solo se il file ha una firma valida di quel soggetto
/// (contro il rinomina-exe, e contro chi copia la firma di un altro file sul suo).
///
/// (correzione 0.18) Si legge anche il <b>nome originale</b> del file (<c>OriginalFilename</c> delle informazioni
/// di versione): in un file firmato è coperto dalla firma, quindi un browser rinominato <c>winword.exe</c> si
/// riconosce lo stesso (<c>msedge.exe</c>).
///
/// Niente rete: la revoca del certificato non si controlla (la verifica non deve aspettare internet). Il
/// risultato si tiene in memoria per poco (<see cref="MemoriaFirme.DurataMs"/>) e solo finché il file è lo
/// stesso: stesso volume e indice del file, stesse date di creazione e modifica, stessa grandezza. Le firme «da
/// catalogo» (molti programmi di Windows) non sono dentro il file: qui valgono come nessuna firma, e quei
/// programmi si riconoscono dal nome (limite noto, scritto nel contratto).
/// </summary>
public static class Firma
{
    private static readonly MemoriaFirme Memoria = new(Identita, LeggiDalFile, Tempo.AdessoUtcMs);

    /// <summary>Il soggetto (CN) di una firma valida di <paramref name="percorsoFile"/>, o <c>null</c>. Mai un'eccezione.</summary>
    public static string? Soggetto(string? percorsoFile) => Leggi(percorsoFile)?.Soggetto;

    /// <summary>(correzione 0.18) Firma valida (soggetto) e nome originale del file, o <c>null</c> se il file non si legge. Mai un'eccezione.</summary>
    public static InfoFirma? Leggi(string? percorsoFile) =>
        string.IsNullOrEmpty(percorsoFile) ? null : Memoria.Leggi(percorsoFile!);

    public static void Dimentica() => Memoria.Dimentica();

    /// <summary>L'identità del file adesso (volume, indice, date, grandezza), o null se non si apre.</summary>
    private static string? Identita(string percorso)
    {
        try
        {
            using SafeFileHandle h = File.OpenHandle(percorso, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            if (!GetFileInformationByHandle(h, out var i)) return null;
            return string.Join('|', i.dwVolumeSerialNumber, i.nFileIndexHigh, i.nFileIndexLow,
                i.ftCreationTime, i.ftLastWriteTime, i.nFileSizeHigh, i.nFileSizeLow);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            return null;
        }
    }

    private static InfoFirma LeggiDalFile(string percorso)
    {
        string? nomeOriginale = null;
        try
        {
            var v = FileVersionInfo.GetVersionInfo(percorso);
            nomeOriginale = string.IsNullOrWhiteSpace(v.OriginalFilename) ? null : v.OriginalFilename.Trim();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException)
        {
        }
        return new InfoFirma(SoggettoDalFile(percorso), nomeOriginale);
    }

    private static string? SoggettoDalFile(string percorso)
    {
        try
        {
            if (!FirmaValida(percorso)) return null;
            using var cert = new X509Certificate2(X509Certificate.CreateFromSignedFile(percorso));
            // Il nome semplice (CN) del soggetto: «Microsoft Corporation».
            var nome = cert.GetNameInfo(X509NameType.SimpleName, forIssuer: false);
            return string.IsNullOrWhiteSpace(nome) ? null : nome;
        }
        catch (CryptographicException)
        {
            // File non firmato.
            return null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException or DllNotFoundException or EntryPointNotFoundException)
        {
            return null;
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BY_HANDLE_FILE_INFORMATION
    {
        public uint dwFileAttributes;
        public long ftCreationTime;
        public long ftLastAccessTime;
        public long ftLastWriteTime;
        public uint dwVolumeSerialNumber;
        public uint nFileSizeHigh;
        public uint nFileSizeLow;
        public uint nNumberOfLinks;
        public uint nFileIndexHigh;
        public uint nFileIndexLow;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetFileInformationByHandle(SafeFileHandle hFile, out BY_HANDLE_FILE_INFORMATION lpFileInformation);

    // ---------- WinVerifyTrust (wintrust.dll) ----------

    private static readonly Guid VerificaGenerica = new("00AAC56B-CD44-11d0-8CC2-00C04FC295EE"); // WINTRUST_ACTION_GENERIC_VERIFY_V2

    private const uint WTD_UI_NONE = 2;
    private const uint WTD_REVOKE_NONE = 0;
    private const uint WTD_CHOICE_FILE = 1;
    private const uint WTD_STATEACTION_IGNORE = 0;
    private const uint WTD_REVOCATION_CHECK_NONE = 0x10;
    private const uint WTD_CACHE_ONLY_URL_RETRIEVAL = 0x1000;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WINTRUST_FILE_INFO
    {
        public uint cbStruct;
        public IntPtr pcwszFilePath;
        public IntPtr hFile;
        public IntPtr pgKnownSubject;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WINTRUST_DATA
    {
        public uint cbStruct;
        public IntPtr pPolicyCallbackData;
        public IntPtr pSIPClientData;
        public uint dwUIChoice;
        public uint fdwRevocationChecks;
        public uint dwUnionChoice;
        public IntPtr pFile;
        public uint dwStateAction;
        public IntPtr hWVTStateData;
        public IntPtr pwszURLReference;
        public uint dwProvFlags;
        public uint dwUIContext;
        public IntPtr pSignatureSettings;
    }

    [DllImport("wintrust.dll", ExactSpelling = true, SetLastError = false)]
    private static extern int WinVerifyTrust(IntPtr hwnd, ref Guid pgActionID, ref WINTRUST_DATA pWVTData);

    /// <summary>La firma dentro il file è valida per Windows (senza interfaccia e senza rete)?</summary>
    private static bool FirmaValida(string percorso)
    {
        var testo = Marshal.StringToHGlobalUni(percorso);
        var fileInfo = IntPtr.Zero;
        try
        {
            var f = new WINTRUST_FILE_INFO { cbStruct = (uint)Marshal.SizeOf<WINTRUST_FILE_INFO>(), pcwszFilePath = testo };
            fileInfo = Marshal.AllocHGlobal(Marshal.SizeOf<WINTRUST_FILE_INFO>());
            Marshal.StructureToPtr(f, fileInfo, false);
            var dati = new WINTRUST_DATA
            {
                cbStruct = (uint)Marshal.SizeOf<WINTRUST_DATA>(),
                dwUIChoice = WTD_UI_NONE,
                fdwRevocationChecks = WTD_REVOKE_NONE,
                dwUnionChoice = WTD_CHOICE_FILE,
                pFile = fileInfo,
                dwStateAction = WTD_STATEACTION_IGNORE,
                dwProvFlags = WTD_REVOCATION_CHECK_NONE | WTD_CACHE_ONLY_URL_RETRIEVAL,
            };
            var azione = VerificaGenerica;
            return WinVerifyTrust(new IntPtr(-1), ref azione, ref dati) == 0; // INVALID_HANDLE_VALUE: nessuna interfaccia
        }
        finally
        {
            if (fileInfo != IntPtr.Zero) Marshal.FreeHGlobal(fileInfo);
            Marshal.FreeHGlobal(testo);
        }
    }
}

/// <summary>(correzione 0.18) Quello che si legge di un eseguibile: il soggetto di una firma valida (o null) e il nome originale del file (o null).</summary>
public sealed record InfoFirma(string? Soggetto, string? NomeOriginale);

/// <summary>
/// (correzione 0.18) La memoria delle firme, logica pura (provata nei test con file finti): un risultato vale solo
/// finché il file ha la stessa identità (volume, indice del file, date, grandezza) <b>e</b> per al massimo
/// <see cref="DurataMs"/>. Così un file sostituito con uno della stessa grandezza e della stessa data non passa: o
/// cambia identità, o al massimo dopo un minuto si rilegge.
/// </summary>
public sealed class MemoriaFirme
{
    /// <summary>Quanto vale un risultato letto, anche se il file sembra lo stesso.</summary>
    public const long DurataMs = 60_000;

    private const int Massimo = 512;

    private readonly Func<string, string?> identita;
    private readonly Func<string, InfoFirma> leggi;
    private readonly Func<long> adessoMs;
    private readonly ConcurrentDictionary<string, (string Identita, long LettaMs, InfoFirma Info)> memoria = new(StringComparer.OrdinalIgnoreCase);

    public MemoriaFirme(Func<string, string?> identita, Func<string, InfoFirma> leggi, Func<long> adessoMs)
    {
        this.identita = identita;
        this.leggi = leggi;
        this.adessoMs = adessoMs;
    }

    /// <summary>Firma e nome originale del file, dalla memoria se il file è lo stesso e il risultato è fresco. Null se il file non si apre.</summary>
    public InfoFirma? Leggi(string percorso)
    {
        var chi = identita(percorso);
        if (chi == null) return null;
        long ora = adessoMs();
        if (memoria.TryGetValue(percorso, out var v) && v.Identita == chi && ora >= v.LettaMs && ora - v.LettaMs < DurataMs) return v.Info;
        var info = leggi(percorso);
        if (memoria.Count > Massimo) memoria.Clear();
        memoria[percorso] = (chi, ora, info);
        return info;
    }

    public void Dimentica() => memoria.Clear();
}
