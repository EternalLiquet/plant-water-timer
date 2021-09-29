import React from 'react';
import { Button, createTheme, CssBaseline, TextField } from '@material-ui/core';
import { ThemeProvider } from '@material-ui/styles';

import firebase from 'firebase/compat/app';
import 'firebase/compat/firestore';
import 'firebase/compat/auth';

import { useAuthState } from 'react-firebase-hooks/auth';
import { useCollectionData } from 'react-firebase-hooks/firestore';
import SignIn from './components/SignIn';

const darkTheme = createTheme({
  palette: {
    type: 'dark',
  }
});

firebase.initializeApp({
  apiKey: "AIzaSyAtQtSFrmXIylCOktouGw2YsnKwGgv6U3k",
  authDomain: "plant0.firebaseapp.com",
  projectId: "plant0",
  storageBucket: "plant0.appspot.com",
  messagingSenderId: "438322523913",
  appId: "1:438322523913:web:8258be948ab81d86616dd8"
});

const auth = firebase.auth();
const firestore = firebase.firestore();

function App() {
  const [user] = useAuthState(auth);

  return (
    <ThemeProvider theme={darkTheme}>
      <CssBaseline />
      <div class="page-bg"/>
      {user ? <TextField/> : <SignIn auth={auth} firebase={firebase}/>}
    </ThemeProvider>
  );
}

export default App;
