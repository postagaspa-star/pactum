using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace Pactum.Sistema;

/// <summary>
/// Il token del dispositivo cifrato con DPAPI dell'utente di Windows: lo può
/// decifrare solo questo utente su questo computer. Sul disco resta in base64.
/// </summary>
public static class Dpapi
{
    private static readonly byte[] Entropia = Encoding.UTF8.GetBytes("Pactum.Computer.token.v1");

    public static string Proteggi(string segreto) =>
        Convert.ToBase64String(Esegui(Encoding.UTF8.GetBytes(segreto), proteggi: true));

    public static string? Svela(string? protetto)
    {
        if (string.IsNullOrEmpty(protetto)) return null;
        try
        {
            return Encoding.UTF8.GetString(Esegui(Convert.FromBase64String(protetto), proteggi: false));
        }
        catch (FormatException)
        {
            return null;
        }
        catch (Win32Exception)
        {
            return null;
        }
    }

    private static byte[] Esegui(byte[] dati, bool proteggi)
    {
        var ingresso = new Win32.DATA_BLOB();
        var entropia = new Win32.DATA_BLOB();
        var uscita = new Win32.DATA_BLOB();
        var hDati = GCHandle.Alloc(dati, GCHandleType.Pinned);
        var hEntropia = GCHandle.Alloc(Entropia, GCHandleType.Pinned);
        try
        {
            ingresso.cbData = dati.Length;
            ingresso.pbData = hDati.AddrOfPinnedObject();
            entropia.cbData = Entropia.Length;
            entropia.pbData = hEntropia.AddrOfPinnedObject();
            bool ok = proteggi
                ? Win32.CryptProtectData(ref ingresso, "Pactum", ref entropia, IntPtr.Zero, IntPtr.Zero, Win32.CRYPTPROTECT_UI_FORBIDDEN, out uscita)
                : Win32.CryptUnprotectData(ref ingresso, IntPtr.Zero, ref entropia, IntPtr.Zero, IntPtr.Zero, Win32.CRYPTPROTECT_UI_FORBIDDEN, out uscita);
            if (!ok) throw new Win32Exception(Marshal.GetLastWin32Error());
            var risultato = new byte[uscita.cbData];
            Marshal.Copy(uscita.pbData, risultato, 0, uscita.cbData);
            return risultato;
        }
        finally
        {
            hDati.Free();
            hEntropia.Free();
            if (uscita.pbData != IntPtr.Zero) Win32.LocalFree(uscita.pbData);
        }
    }
}
