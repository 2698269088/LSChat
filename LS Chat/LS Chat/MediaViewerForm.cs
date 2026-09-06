using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace LS_Chat
{
    /// <summary>图片/视频查看窗口：图片直接预览，视频提取到临时文件用系统播放器播放，均可保存。</summary>
    public class MediaViewerForm : Form
    {
        private const long MaxVideoBytes = 40L * 1024 * 1024;

        private readonly ChatMessage _message;
        private readonly string _tempPath;
        private readonly PictureBox _picture;
        private readonly Label _lblVideo;
        private readonly Label _lblStatus;

        public MediaViewerForm(ChatMessage message)
        {
            _message = message;
            bool isVideo = message.type == ChatMessage.TypeVideo;

            Text = isVideo ? "视频查看" : "图片查看";
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new Size(720, 560);
            MinimumSize = new Size(400, 320);

            _picture = new PictureBox
            {
                Dock = DockStyle.Fill,
                SizeMode = PictureBoxSizeMode.Zoom,
                BackColor = Color.Black
            };
            _lblVideo = new Label
            {
                Dock = DockStyle.Fill,
                TextAlign = ContentAlignment.MiddleCenter,
                Text = "视频消息\r\n点击下方「播放」使用系统播放器查看",
                ForeColor = Color.White,
                BackColor = Color.Black,
                Visible = isVideo
            };
            _picture.Visible = !isVideo;
            _picture.Controls.Add(_lblVideo);

            var bottom = new Panel { Dock = DockStyle.Bottom, Height = 46 };
            _lblStatus = new Label
            {
                AutoSize = true,
                Location = new Point(12, 14)
            };
            var btnPlay = new Button
            {
                Text = "播放",
                Dock = DockStyle.Right,
                Width = 90
            };
            btnPlay.Visible = isVideo;
            btnPlay.Click += (s, e) => PlayVideo();
            var btnSave = new Button
            {
                Text = "保存...",
                Dock = DockStyle.Right,
                Width = 90
            };
            btnSave.Click += (s, e) => SaveAs();
            bottom.Controls.Add(_lblStatus);
            bottom.Controls.Add(btnSave);
            bottom.Controls.Add(btnPlay);

            Controls.Add(_picture);
            Controls.Add(bottom);

            if (isVideo)
            {
                _tempPath = Path.Combine(Path.GetTempPath(), "lschat_video_" + message.id + ".mp4");
            }
            else
            {
                _tempPath = null;
                try
                {
                    byte[] bytes = Convert.FromBase64String(message.content);
                    using (var stream = new MemoryStream(bytes))
                    {
                        _picture.Image = Image.FromStream(stream);
                    }
                    _lblStatus.Text = FormatSize(bytes.Length);
                }
                catch (Exception ex)
                {
                    _lblStatus.Text = "图片加载失败：" + ex.Message;
                }
            }
        }

        private void PlayVideo()
        {
            try
            {
                EnsureTempFile();
                Process.Start(_tempPath);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "播放失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private void SaveAs()
        {
            bool isVideo = _message.type == ChatMessage.TypeVideo;
            using (var dialog = new SaveFileDialog
            {
                Filter = isVideo ? "MP4 视频|*.mp4" : "JPEG 图片|*.jpg",
                FileName = (isVideo ? "video_" : "image_") + _message.id + (isVideo ? ".mp4" : ".jpg")
            })
            {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                try
                {
                    File.WriteAllBytes(dialog.FileName, Convert.FromBase64String(_message.content));
                    _lblStatus.Text = "已保存到 " + dialog.FileName;
                }
                catch (Exception ex)
                {
                    MessageBox.Show(this, ex.Message, "保存失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            }
        }

        private void EnsureTempFile()
        {
            if (File.Exists(_tempPath)) return;
            byte[] bytes = Convert.FromBase64String(_message.content);
            if (bytes.LongLength > MaxVideoBytes) throw new InvalidDataException("视频过大，无法播放");
            File.WriteAllBytes(_tempPath, bytes);
        }

        private static string FormatSize(long bytes)
        {
            if (bytes >= 1024 * 1024) return (bytes / 1024.0 / 1024.0).ToString("0.0") + " MB";
            if (bytes >= 1024) return (bytes / 1024.0).ToString("0.0") + " KB";
            return bytes + " B";
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            base.OnFormClosed(e);
            if (_picture.Image != null) _picture.Image.Dispose();
            try
            {
                if (_tempPath != null && File.Exists(_tempPath)) File.Delete(_tempPath);
            }
            catch
            {
                // 临时文件删除失败不影响使用
            }
        }
    }
}
