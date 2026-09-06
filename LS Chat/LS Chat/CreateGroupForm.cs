using System;
using System.Drawing;
using System.Windows.Forms;

namespace LS_Chat
{
    /// <summary>创建群窗口：输入群名称并选择入群方式。</summary>
    public class CreateGroupForm : Form
    {
        private readonly TextBox _name;
        private readonly RadioButton _radioApproval;
        private readonly RadioButton _radioOpen;

        public string GroupName { get; private set; }
        public string JoinPolicy { get; private set; }

        public CreateGroupForm()
        {
            Text = "创建群聊";
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new Size(360, 235);
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;

            Controls.Add(new Label
            {
                Text = "群名称：",
                Left = 20,
                Top = 18,
                Width = 320
            });
            _name = new TextBox { Left = 20, Top = 43, Width = 320 };
            Controls.Add(_name);
            Controls.Add(new Label
            {
                Text = "入群方式（之后可在群管理中修改）：",
                Left = 20,
                Top = 82,
                Width = 320
            });
            _radioApproval = new RadioButton
            {
                Text = "需要管理员审核（用户申请后需同意才能加入）",
                Left = 30,
                Top = 107,
                Width = 310,
                Checked = true
            };
            _radioOpen = new RadioButton
            {
                Text = "允许直接加入（无需确认）",
                Left = 30,
                Top = 132,
                Width = 310
            };
            Controls.Add(_radioApproval);
            Controls.Add(_radioOpen);

            var btnOk = new Button { Text = "创建", Left = 185, Top = 180, Width = 75 };
            btnOk.Click += BtnOk_Click;
            var btnCancel = new Button
            {
                Text = "取消",
                Left = 270,
                Top = 180,
                Width = 75,
                DialogResult = DialogResult.Cancel
            };
            Controls.Add(btnOk);
            Controls.Add(btnCancel);
            AcceptButton = btnOk;
            CancelButton = btnCancel;
        }

        private void BtnOk_Click(object sender, EventArgs e)
        {
            string name = _name.Text.Trim();
            if (name.Length == 0 || name.Length > 32)
            {
                MessageBox.Show(this, "群名称需为 1-32 个字符", "提示",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            GroupName = name;
            JoinPolicy = _radioOpen.Checked ? "open" : "approval";
            DialogResult = DialogResult.OK;
            Close();
        }
    }
}
