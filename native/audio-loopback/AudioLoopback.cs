// Captures what the computer plays (the other participants of a call) with WASAPI loopback and writes it to
// stdout as raw 32-bit float PCM, interleaved, at the device's own rate and channel count.
//
//   audio-loopback.exe --info          prints "<rate> <channels> <device name>" of the default output device
//   audio-loopback.exe [--seconds N]   captures until killed, stdout is closed, or N seconds have passed
//
// Windows sends no loopback data while nothing plays, so silence is written by the clock: the stream always
// covers the real time since the start and stays in step with the microphone recording.
// Built with the C# 5 compiler that ships with Windows (packaging\build-loopback.ps1); no other dependencies.
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

static class AudioLoopback
{
    const int EDataFlowRender = 0;
    const int ERoleConsole = 0;
    const int ClsctxAll = 23;
    const int SharedMode = 0;
    const int StreamFlagsLoopback = 0x00020000;
    const int BufferFlagsSilent = 0x2;
    const long BufferDuration = 10000000; // 1 s in 100 ns units

    // Silence is written once no data came for this long, so normal delivery jitter never inserts gaps
    const double SilenceAfterSeconds = 0.2;

    static readonly Guid IidAudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    static readonly Guid IidAudioCaptureClient = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
    static readonly Guid FloatSubFormat = new Guid("00000003-0000-0010-8000-00aa00389b71");

    static int Main(string[] args)
    {
        try
        {
            double maxSeconds = double.MaxValue;
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == "--info") return PrintInfo();
                if (args[i] == "--seconds" && i + 1 < args.Length) maxSeconds = double.Parse(args[++i], System.Globalization.CultureInfo.InvariantCulture);
            }
            return Capture(maxSeconds);
        }
        catch (Exception e)
        {
            Console.Error.WriteLine("audio-loopback: " + e.Message);
            return 1;
        }
    }

    static IMMDevice DefaultOutput()
    {
        IMMDeviceEnumerator enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        IMMDevice device;
        enumerator.GetDefaultAudioEndpoint(EDataFlowRender, ERoleConsole, out device);
        return device;
    }

    static IAudioClient Activate(IMMDevice device)
    {
        Guid iid = IidAudioClient;
        object client;
        device.Activate(ref iid, ClsctxAll, IntPtr.Zero, out client);
        return (IAudioClient)client;
    }

    struct Format
    {
        public int Rate;
        public int Channels;
        public int BitsPerSample;
        public bool IsFloat;
        public int BlockAlign;
    }

    static Format ReadFormat(IntPtr waveFormat)
    {
        Format f = new Format();
        int tag = Marshal.ReadInt16(waveFormat, 0) & 0xFFFF;
        f.Channels = Marshal.ReadInt16(waveFormat, 2);
        f.Rate = Marshal.ReadInt32(waveFormat, 4);
        f.BlockAlign = Marshal.ReadInt16(waveFormat, 12);
        f.BitsPerSample = Marshal.ReadInt16(waveFormat, 14);
        f.IsFloat = tag == 3;
        if (tag == 0xFFFE) // WAVE_FORMAT_EXTENSIBLE: the sample type is the sub-format GUID at offset 24
        {
            byte[] guid = new byte[16];
            Marshal.Copy(new IntPtr(waveFormat.ToInt64() + 24), guid, 0, 16);
            f.IsFloat = new Guid(guid) == FloatSubFormat;
        }
        if (!(f.IsFloat && f.BitsPerSample == 32) && !(!f.IsFloat && f.BitsPerSample == 16))
            throw new NotSupportedException("unsupported output format: " + f.BitsPerSample + " bit" + (f.IsFloat ? " float" : ""));
        return f;
    }

    static int PrintInfo()
    {
        IMMDevice device = DefaultOutput();
        IntPtr waveFormat;
        Activate(device).GetMixFormat(out waveFormat);
        Format f = ReadFormat(waveFormat);
        Marshal.FreeCoTaskMem(waveFormat);
        Console.WriteLine(f.Rate + " " + f.Channels + " " + FriendlyName(device));
        return 0;
    }

    static int Capture(double maxSeconds)
    {
        IMMDevice device = DefaultOutput();
        IAudioClient client = Activate(device);
        IntPtr waveFormat;
        client.GetMixFormat(out waveFormat);
        Format f = ReadFormat(waveFormat);
        client.Initialize(SharedMode, StreamFlagsLoopback, BufferDuration, 0, waveFormat, IntPtr.Zero);
        Marshal.FreeCoTaskMem(waveFormat);
        Guid iid = IidAudioCaptureClient;
        object service;
        client.GetService(ref iid, out service);
        IAudioCaptureClient capture = (IAudioCaptureClient)service;

        Stream output = Console.OpenStandardOutput();
        int frameBytes = 4 * f.Channels; // float32 out
        byte[] buffer = new byte[f.Rate * frameBytes]; // up to 1 s per packet
        long framesWritten = 0;
        Stopwatch clock = Stopwatch.StartNew();
        double lastData = 0;

        client.Start();
        try
        {
            while (clock.Elapsed.TotalSeconds < maxSeconds)
            {
                Thread.Sleep(10);
                bool gotData = false;
                uint packet;
                capture.GetNextPacketSize(out packet);
                while (packet > 0)
                {
                    IntPtr data;
                    uint frames, flags;
                    ulong devicePosition, qpcPosition;
                    capture.GetBuffer(out data, out frames, out flags, out devicePosition, out qpcPosition);
                    int bytes = (int)frames * frameBytes;
                    if (bytes > buffer.Length) buffer = new byte[bytes];
                    if ((flags & BufferFlagsSilent) != 0) Array.Clear(buffer, 0, bytes);
                    else CopyAsFloat(data, (int)frames, f, buffer);
                    capture.ReleaseBuffer(frames);
                    output.Write(buffer, 0, bytes);
                    framesWritten += frames;
                    gotData = true;
                    capture.GetNextPacketSize(out packet);
                }
                double now = clock.Elapsed.TotalSeconds;
                if (gotData)
                {
                    lastData = now;
                }
                else if (now - lastData >= SilenceAfterSeconds)
                {
                    long behind = (long)(now * f.Rate) - framesWritten;
                    if (behind > 0)
                    {
                        WriteSilence(output, behind * frameBytes);
                        framesWritten += behind;
                    }
                }
                output.Flush();
            }
        }
        catch (IOException)
        {
            // The reader (ffmpeg) is gone: stop quietly
        }
        finally
        {
            client.Stop();
        }
        return 0;
    }

    static void CopyAsFloat(IntPtr data, int frames, Format f, byte[] target)
    {
        int samples = frames * f.Channels;
        if (f.IsFloat)
        {
            Marshal.Copy(data, target, 0, samples * 4);
            return;
        }
        short[] pcm = new short[samples];
        Marshal.Copy(data, pcm, 0, samples);
        for (int i = 0; i < samples; i++)
        {
            byte[] value = BitConverter.GetBytes(pcm[i] / 32768f);
            Buffer.BlockCopy(value, 0, target, i * 4, 4);
        }
    }

    static void WriteSilence(Stream output, long bytes)
    {
        byte[] zeros = new byte[64 * 1024];
        while (bytes > 0)
        {
            int n = (int)Math.Min(bytes, zeros.Length);
            output.Write(zeros, 0, n);
            bytes -= n;
        }
    }

    // PKEY_Device_FriendlyName
    static string FriendlyName(IMMDevice device)
    {
        IPropertyStore store;
        device.OpenPropertyStore(0, out store);
        PropertyKey key = new PropertyKey();
        key.FormatId = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");
        key.PropertyId = 14;
        PropVariant value;
        store.GetValue(ref key, out value);
        return value.ValueType == 31 ? Marshal.PtrToStringUni(value.Pointer) : "Default output device"; // VT_LPWSTR
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PropertyKey
    {
        public Guid FormatId;
        public int PropertyId;
    }

    [StructLayout(LayoutKind.Explicit)]
    struct PropVariant
    {
        [FieldOffset(0)] public short ValueType;
        [FieldOffset(8)] public IntPtr Pointer;
        [FieldOffset(16)] public IntPtr Padding;
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumerator { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDeviceEnumerator
    {
        void EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        void GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IMMDevice
    {
        void Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
        void OpenPropertyStore(int access, out IPropertyStore properties);
    }

    [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPropertyStore
    {
        void GetCount(out int count);
        void GetAt(int index, out PropertyKey key);
        void GetValue(ref PropertyKey key, out PropVariant value);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient
    {
        void Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
        void GetBufferSize(out uint frames);
        void GetStreamLatency(out long latency);
        void GetCurrentPadding(out uint frames);
        void IsFormatSupported(int shareMode, IntPtr format, out IntPtr closestMatch);
        void GetMixFormat(out IntPtr format);
        void GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        void Start();
        void Stop();
        void Reset();
        void SetEventHandle(IntPtr handle);
        void GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient
    {
        void GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        void ReleaseBuffer(uint frames);
        void GetNextPacketSize(out uint frames);
    }
}
