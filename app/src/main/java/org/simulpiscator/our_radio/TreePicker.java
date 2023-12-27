package org.simulpiscator.our_radio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TreePicker {

    static class Node {
        String name;
        String value = null;
        final Node parent;
        List<Node> children = new ArrayList<Node>();

        Node(Node _parent, String _name) {
            parent = _parent;
            name = _name;
        }
        interface Visitor { void visit(Node node); }
        void visit(Visitor v) {
            v.visit(this);
            for(Node child : children)
                child.visit(v);
        }
    };

    interface OnClickListener {
        void onClick(Node node);
    }
    static class Builder extends AlertDialog.Builder {
        Builder(Context context) {
            super(context);
        }
        Builder setRoot(final Node root, final TreePicker.OnClickListener action) {
            CharSequence[] s = new CharSequence[root.children.size()];
            int i = 0;
            for(Node child : root.children)
                s[i++] = child.name;
            setItems(s, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Node node = root.children.get(which);
                    if(node.value != null)
                        action.onClick(node);
                    else
                        setRoot(node, action).create().show();
                }
            });
            setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if(root.parent != null)
                        setRoot(root.parent, action).create().show();
                }
            });
            setCancelable(true);
            return this;
        }
    }

    static Node MakeTree(Collection<String> paths, String commonPrefix) {
        Node root = new Node(null, commonPrefix);
        for(String path : paths)
            addLeaf(root, path.substring(commonPrefix.length()), path);
        return root;
    }

    private static void addLeaf(Node node, String leafPath, String value) {
        String[] el = leafPath.split("/", 2);
        if(el.length > 0) {
            Node n = null;
            for(Node child : node.children) {
                if(child.name.equals(el[0])) {
                    n = child;
                    break;
                }
            }
            if(n == null) {
                n = new Node(node, el[0]);
                node.children.add(n);
            }
            if(el.length > 1)
                addLeaf(n, el[1], value);
            else
                n.value = value;
        }
    }
}
